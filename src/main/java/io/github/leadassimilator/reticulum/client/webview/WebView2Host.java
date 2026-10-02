package io.github.leadassimilator.reticulum.client.webview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The editor inside the game window on Windows: Edge's WebView2, driven through its COM interfaces with JNA (both
 * already part of Windows and Minecraft). It shows one of two ways ({@link ReticulumClientConfig#EDITOR_DISPLAY}): as a
 * child window over the game, or drawn into the game's own frame ({@link CompositionView}), which also works in
 * exclusive fullscreen. Everything runs on the render thread, which owns the game window; WebView2's callbacks arrive
 * through the window's message loop, which GLFW pumps every frame.
 *
 * <p>Method numbers are the vtable slots from the SDK's WebView2.h (IUnknown takes 0-2).
 */
final class WebView2Host {
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    // ICoreWebView2Environment
    private static final int ENV_CREATE_CONTROLLER = 3;
    // ICoreWebView2Environment3
    private static final String IID_ENVIRONMENT3 = "{80a22ae3-be7c-4ce2-afe1-5a50056cdeeb}";
    private static final int ENV3_CREATE_COMPOSITION_CONTROLLER = 9;
    // ICoreWebView2CompositionController
    private static final int COMP_PUT_ROOT_VISUAL_TARGET = 4;
    private static final int COMP_SEND_MOUSE_INPUT = 5;
    private static final int COMP_GET_SYSTEM_CURSOR_ID = 8;
    // ICoreWebView2Controller
    private static final String IID_CONTROLLER = "{4d00c0d1-9434-4eb6-8078-8697a560334f}";
    private static final int CTL_PUT_IS_VISIBLE = 4;
    private static final int CTL_PUT_BOUNDS = 6;
    private static final int CTL_MOVE_FOCUS = 12;
    private static final int CTL_CLOSE = 24;
    private static final int CTL_GET_CORE_WEBVIEW2 = 25;
    // ICoreWebView2Controller2
    private static final String IID_CONTROLLER2 = "{c979903e-d4ca-4228-92eb-47ee3fa96eab}";
    private static final int CTL2_PUT_DEFAULT_BACKGROUND_COLOR = 27;
    // ICoreWebView2
    private static final int WV_NAVIGATE = 5;
    private static final int WV_ADD_NAVIGATION_COMPLETED = 15;

    private enum State { NONE, CREATING, READY, FAILED }

    private static State state = State.NONE;
    @Nullable
    private static Pointer environment;
    @Nullable
    private static Pointer controller;
    /** The composition controller, when the view is drawn into the game's frame. */
    @Nullable
    private static Pointer composition;
    @Nullable
    private static Pointer webview;
    /** What to show once it is ready (or now, when it is). */
    @Nullable
    private static String pendingUrl;
    private static boolean visible;
    /**
     * Whether the page has finished its first load. Until then the view stays hidden and the game draws a placeholder:
     * shown earlier, it flashes the game through (nothing painted yet) and then WebView2's own default background.
     */
    private static boolean loaded;
    private static int[] bounds = {0, 0, 1, 1};
    @Nullable
    private static NativeLibrary loader;
    /** This session's view is drawn into the game's frame (decided when it is created). */
    private static boolean inFrame;
    /** Drawing into the frame failed once this session: the window is used from then on. */
    private static boolean frameFailed;

    /**
     * COM callback objects. WebView2 may call one after the editor was closed (a world left while it was still
     * starting), so none is ever let go: their memory must outlive any call. There are a handful per session.
     */
    private static final java.util.List<Com.Handler> HANDLERS = new java.util.ArrayList<>();
    /** Bumped by every create and close; a callback from an earlier one releases what it was given and does nothing. */
    private static int generation;

    private static Com.Handler keep(Com.Handler handler) {
        HANDLERS.add(handler);
        return handler;
    }

    private WebView2Host() {
    }

    /** Windows with a WebView2 runtime we can reach, and nothing has failed yet. */
    static boolean supported() {
        return Platform.isWindows() && state != State.FAILED;
    }

    static boolean ready() {
        return state == State.READY;
    }

    /** Ready and the page has loaded, so the view is on screen when asked to be. */
    static boolean shown() {
        return state == State.READY && loaded && (!inFrame || CompositionView.frame() != null);
    }

    /** Whether the view is (or will be, once created) drawn into the game's frame rather than shown as a window. */
    static boolean drawsInFrame() {
        if (state == State.NONE) {
            return wantsFrame();
        }
        return inFrame;
    }

    private static boolean wantsFrame() {
        return !frameFailed && ReticulumClientConfig.EDITOR_DISPLAY.get() != ReticulumClientConfig.EditorDisplay.WINDOW;
    }

    /** The view's latest frame to draw, when it is drawn into the game's frame and one has arrived. */
    @Nullable
    static ResourceLocation frame() {
        return inFrame && state == State.READY && loaded ? CompositionView.frame() : null;
    }

    /** Whether the game draws the view now (so mouse input goes through {@link #mouse}). */
    static boolean drawnInFrame() {
        return inFrame && state == State.READY;
    }

    // ---------------------------------------------------------------- input, when drawn into the frame

    /** COREWEBVIEW2_MOUSE_EVENT_KIND: the Windows mouse messages. */
    static final int MOUSE_MOVE = 0x200;
    static final int MOUSE_WHEEL = 0x20A;
    static final int MOUSE_HORIZONTAL_WHEEL = 0x20E;
    static final int MOUSE_LEAVE = 0x2A3;
    private static final int[] BUTTON_DOWN = {0x201, 0x204, 0x207, 0x20B, 0x20B};
    private static final int[] BUTTON_KEY = {0x1, 0x2, 0x10, 0x20, 0x40};

    /** user32's GetDoubleClickTime (the Function holds its library). */
    private static final Function GET_DOUBLE_CLICK_TIME = Function.getFunction("user32", "GetDoubleClickTime");

    private static int lastDownKind;
    private static long lastDownTime;
    private static int lastDownX;
    private static int lastDownY;

    /** A mouse event at view pixels {@code x}, {@code y}; {@code keys} holds the buttons down and Shift/Ctrl. */
    static void mouse(int kind, int keys, int data, int x, int y) {
        if (!drawnInFrame()) {
            return;
        }
        Point point = new Point();
        point.x = x;
        point.y = y;
        // Shift and Ctrl from Windows itself: the game's own key state is stale while the page has the keyboard.
        keys &= ~0xC;
        keys |= User32.INSTANCE.GetAsyncKeyState(0x10) < 0 ? 0x4 : 0; // VK_SHIFT
        keys |= User32.INSTANCE.GetAsyncKeyState(0x11) < 0 ? 0x8 : 0; // VK_CONTROL
        Com.call(composition, COMP_SEND_MOUSE_INPUT, kind, keys, data, point);
    }

    /** A button (GLFW numbering) going down or up at view pixels; a second press soon after is a double click. */
    static void button(int button, boolean down, int keys, int x, int y) {
        if (button < 0 || button >= BUTTON_DOWN.length) {
            return;
        }
        int kind = BUTTON_DOWN[button];
        // The X buttons share their messages; the data says which.
        int data = button == 3 ? 1 : button == 4 ? 2 : 0;
        if (!down) {
            mouse(kind + 1, keys & ~BUTTON_KEY[button], data, x, y);
            return;
        }
        keys |= BUTTON_KEY[button];
        long now = System.currentTimeMillis();
        // As Windows decides it: the same button again within the double-click time and box.
        int boxX = User32.INSTANCE.GetSystemMetrics(36) / 2;
        int boxY = User32.INSTANCE.GetSystemMetrics(37) / 2;
        boolean twice = kind == lastDownKind && now - lastDownTime <= doubleClickTime()
                && Math.abs(x - lastDownX) <= boxX && Math.abs(y - lastDownY) <= boxY;
        mouse(twice ? kind + 2 : kind, keys, data, x, y);
        // A third press starts over, as on Windows.
        lastDownKind = twice ? 0 : kind;
        lastDownTime = now;
        lastDownX = x;
        lastDownY = y;
    }

    private static int doubleClickTime() {
        return GET_DOUBLE_CLICK_TIME.invokeInt(new Object[0]);
    }

    /** Gives the keyboard to the view (a click in it). */
    static void focusView() {
        if (state == State.READY) {
            // COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC
            Com.call(controller, CTL_MOVE_FOCUS, 0);
        }
    }

    /**
     * Whether a mouse button (GLFW numbering) is down, asked of Windows: while the page drags, Chromium captures the
     * mouse to its own window, so the game never hears the button come up.
     */
    static boolean buttonDown(int button) {
        boolean swapped = User32.INSTANCE.GetSystemMetrics(23) != 0; // SM_SWAPBUTTON
        int key = switch (button) {
            case 0 -> swapped ? 0x02 : 0x01; // VK_LBUTTON, VK_RBUTTON
            case 1 -> swapped ? 0x01 : 0x02;
            case 2 -> 0x04; // VK_MBUTTON
            case 3 -> 0x05; // VK_XBUTTON1
            case 4 -> 0x06; // VK_XBUTTON2
            default -> 0;
        };
        return key != 0 && User32.INSTANCE.GetAsyncKeyState(key) < 0;
    }

    /** The GLFW cursor shape the page asks for at the mouse (0 for the default arrow). */
    static int cursor() {
        if (!drawnInFrame()) {
            return 0;
        }
        IntByReference id = new IntByReference();
        if (Com.call(composition, COMP_GET_SYSTEM_CURSOR_ID, id) < 0) {
            return 0;
        }
        return switch (id.getValue()) {
            case 32513 -> GLFW.GLFW_IBEAM_CURSOR; // IDC_IBEAM
            case 32515 -> GLFW.GLFW_CROSSHAIR_CURSOR; // IDC_CROSS
            case 32649 -> GLFW.GLFW_POINTING_HAND_CURSOR; // IDC_HAND
            case 32644 -> GLFW.GLFW_RESIZE_EW_CURSOR; // IDC_SIZEWE
            case 32645 -> GLFW.GLFW_RESIZE_NS_CURSOR; // IDC_SIZENS
            case 32642 -> GLFW.GLFW_RESIZE_NWSE_CURSOR; // IDC_SIZENWSE
            case 32643 -> GLFW.GLFW_RESIZE_NESW_CURSOR; // IDC_SIZENESW
            case 32646 -> GLFW.GLFW_RESIZE_ALL_CURSOR; // IDC_SIZEALL
            case 32648 -> GLFW.GLFW_NOT_ALLOWED_CURSOR; // IDC_NO
            default -> 0;
        };
    }

    /** A Win32 POINT passed by value. */
    @Structure.FieldOrder({"x", "y"})
    public static class Point extends Structure implements Structure.ByValue {
        public int x;
        public int y;
    }

    /** Shows the view over the game at these window pixels, loading {@code url} (null keeps the current page). */
    static void show(@Nullable String url, int x, int y, int width, int height) {
        if (url != null) {
            pendingUrl = url;
        }
        visible = true;
        bounds = new int[] {x, y, x + Math.max(1, width), y + Math.max(1, height)};
        switch (state) {
            case NONE -> create();
            case READY -> apply();
            default -> {
            }
        }
    }

    /** Hides the view and gives the keyboard back to the game; the page stays loaded (and connected) for next time. */
    static void hide() {
        visible = false;
        GLFW.glfwSetWindowAttrib(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_TRUE);
        if (state == State.READY) {
            Com.call(controller, CTL_PUT_IS_VISIBLE, 0);
            User32.INSTANCE.SetFocus(gameWindow());
        }
    }

    /** Gives the keyboard to the game window (clicking the bar above the view), so the game's keys work there. */
    static void focusGame() {
        if (Platform.isWindows()) {
            User32.INSTANCE.SetFocus(gameWindow());
        }
    }

    /** Closes the browser for good (leaving the world, or the game closing), releasing what WebView2 handed out. */
    static void close() {
        generation++;
        if (state == State.READY) {
            Com.call(controller, CTL_CLOSE);
        }
        Com.release(webview);
        Com.release(controller);
        Com.release(composition);
        Com.release(environment);
        if (inFrame) {
            CompositionView.close();
        }
        controller = null;
        composition = null;
        webview = null;
        environment = null;
        pendingUrl = null;
        state = State.NONE;
        visible = false;
        loaded = false;
        inFrame = false;
    }

    // ---------------------------------------------------------------- creation

    private static void create() {
        state = State.CREATING;
        int created = ++generation;
        try {
            int hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue();
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) {
                throw new IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(hr));
            }
            // Absolute: the game folder can be relative ("." in development), and WebView2 would then look for its
            // folders beside javaw.exe, where it can't write.
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("reticulum");
            Path profile = Files.createDirectories(gameDir.resolve("webview2-profile"));
            // Held for the session: JNA caches libraries weakly and unloads one nothing refers to.
            loader = NativeLibrary.getInstance(extractLoader(gameDir.resolve("webview2")).toString());
            Com.Handler environmentHandler = keep(new Com.Handler((Com.Completed) (self, result, made) -> {
                if (created != generation) {
                    // Closed (or started again) meanwhile: what this call made isn't wanted.
                    return Com.S_OK;
                }
                if (result < 0 || made == null) {
                    fail("creating the WebView2 environment", result);
                    return Com.S_OK;
                }
                Com.addRef(made);
                environment = made;
                createController(created);
                return Com.S_OK;
            }));
            Function createEnvironment = loader.getFunction("CreateCoreWebView2EnvironmentWithOptions");
            // The profile (cookies, so the editor stays logged in) lives in the game folder, not beside javaw.exe.
            int r = createEnvironment.invokeInt(new Object[] {null, new WString(profile.toString()), null,
                    environmentHandler.pointer()});
            if (r < 0) {
                fail("starting WebView2 (is the WebView2 runtime installed?)", r);
            }
        } catch (RuntimeException | Error | IOException e) {
            Reticulum.LOGGER.warn("The in-game editor isn't available; using the browser instead", e);
            fail();
        }
    }

    /** A controller drawing into the game's frame when that is wanted and works, else a child window. */
    private static void createController(int created) {
        Pointer target = null;
        Pointer environment3 = null;
        if (wantsFrame()) {
            environment3 = Com.query(environment, IID_ENVIRONMENT3);
            target = environment3 == null ? null : CompositionView.start(width(), height());
            if (target == null) {
                frameFailed = true;
                Com.release(environment3);
                environment3 = null;
            }
        }
        inFrame = target != null;
        Pointer visual = target;
        Com.Handler controllerHandler = keep(new Com.Handler((Com.Completed) (self, result, controllerMade) -> {
            if (created != generation) {
                if (controllerMade != null) {
                    // A controller nobody will show: close it rather than leave its browser process running.
                    Pointer plain = visual == null ? controllerMade : Com.query(controllerMade, IID_CONTROLLER);
                    Com.call(plain, CTL_CLOSE);
                }
                return Com.S_OK;
            }
            if (visual == null) {
                onController(result, controllerMade);
            } else {
                onCompositionController(result, controllerMade, visual);
            }
            return Com.S_OK;
        }));
        int r = environment3 != null
                ? Com.call(environment3, ENV3_CREATE_COMPOSITION_CONTROLLER, gameWindow().getPointer(), controllerHandler.pointer())
                : Com.call(environment, ENV_CREATE_CONTROLLER, gameWindow().getPointer(), controllerHandler.pointer());
        Com.release(environment3);
        if (r < 0) {
            fail("creating the WebView2 controller", r);
        }
    }

    private static void onCompositionController(int result, @Nullable Pointer made, Pointer visual) {
        if (result < 0 || made == null) {
            fail("creating the WebView2 controller", result);
            return;
        }
        Com.addRef(made);
        composition = made;
        Com.call(composition, COMP_PUT_ROOT_VISUAL_TARGET, visual);
        Pointer plain = Com.query(composition, IID_CONTROLLER);
        onController(plain == null ? -1 : Com.S_OK, plain);
        if (plain != null) {
            // onController took its own reference.
            Com.release(plain);
        }
        if (state == State.READY && !CompositionView.capture()) {
            // Capture can't start here: the window it is, from now on.
            frameFailed = true;
            String url = pendingUrl;
            boolean wasVisible = visible;
            int[] was = bounds;
            close();
            if (wasVisible) {
                show(url, was[0], was[1], was[2] - was[0], was[3] - was[1]);
            }
        }
    }

    private static void onController(int result, @Nullable Pointer made) {
        if (result < 0 || made == null) {
            fail("creating the WebView2 controller", result);
            return;
        }
        Com.addRef(made);
        controller = made;
        paintBehindPage();
        PointerByReference core = new PointerByReference();
        Com.call(controller, CTL_GET_CORE_WEBVIEW2, core);
        webview = core.getValue();
        Com.Handler navigationHandler = keep(new Com.Handler((Com.Event) (self, sender, args) -> {
            if (!loaded && state == State.READY) {
                loaded = true;
                apply();
            }
            return Com.S_OK;
        }));
        Com.call(webview, WV_ADD_NAVIGATION_COMPLETED, navigationHandler.pointer(), new Memory(8));
        state = State.READY;
        apply();
    }

    /** Brings the view in line with what was asked for: page, bounds, visibility and focus. */
    private static void apply() {
        if (pendingUrl != null) {
            Com.call(webview, WV_NAVIGATE, new WString(pendingUrl));
            pendingUrl = null;
        }
        if (inFrame) {
            // The page's input window takes the keyboard when clicked, and GLFW minimizes a fullscreen window that
            // loses it; while the editor shows, it mustn't (hide puts it back).
            GLFW.glfwSetWindowAttrib(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_AUTO_ICONIFY,
                    visible ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
            // Drawn off screen: the page is laid out at the size it is drawn, from the origin.
            CompositionView.size(width(), height());
            Rect rect = new Rect();
            rect.right = width();
            rect.bottom = height();
            Com.call(controller, CTL_PUT_BOUNDS, rect);
            // It renders only while visible, and it draws into a texture nobody sees until the screen does.
            Com.call(controller, CTL_PUT_IS_VISIBLE, visible ? 1 : 0);
            return;
        }
        // GLFW rebuilds the window's style when it enters or leaves fullscreen, dropping WS_CLIPCHILDREN.
        clipChildren();
        Com.call(controller, CTL_PUT_BOUNDS, rect());
        Com.call(controller, CTL_PUT_IS_VISIBLE, visible && loaded ? 1 : 0);
        if (visible && loaded) {
            // COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC
            Com.call(controller, CTL_MOVE_FOCUS, 0);
        }
    }

    private static int width() {
        return bounds[2] - bounds[0];
    }

    private static int height() {
        return bounds[3] - bounds[1];
    }

    /**
     * WebView2 fills the view with white before the page is composited; make that the placeholder's dark color, so
     * showing the view never flashes white.
     */
    private static void paintBehindPage() {
        Pointer controller2 = Com.query(controller, IID_CONTROLLER2);
        if (controller2 == null) {
            return;
        }
        Color color = new Color();
        color.a = (byte) 0xFF;
        color.r = (byte) (EmbeddedView.BACKGROUND >> 16);
        color.g = (byte) (EmbeddedView.BACKGROUND >> 8);
        color.b = (byte) EmbeddedView.BACKGROUND;
        Com.call(controller2, CTL2_PUT_DEFAULT_BACKGROUND_COLOR, color);
        Com.release(controller2);
    }

    /** COREWEBVIEW2_COLOR, passed by value. */
    @Structure.FieldOrder({"a", "r", "g", "b"})
    public static class Color extends Structure implements Structure.ByValue {
        public byte a;
        public byte r;
        public byte g;
        public byte b;
    }

    private static void fail(String what, int hresult) {
        Reticulum.LOGGER.warn("In-game editor: {} failed (0x{}); using the browser instead", what, Integer.toHexString(hresult));
        fail();
    }

    /** Gives up on WebView2 for this session; the editor opens in the browser, once (however the failure arrived). */
    private static void fail() {
        if (state == State.FAILED) {
            return;
        }
        state = State.FAILED;
        if (visible) {
            Minecraft.getInstance().execute(EditorScreen::fallBackToBrowser);
        }
    }

    // ---------------------------------------------------------------- Windows plumbing

    private static WinDef.HWND gameWindow() {
        return new WinDef.HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow())));
    }

    /** Without WS_CLIPCHILDREN, the game's OpenGL frames can paint over the web view. */
    private static void clipChildren() {
        WinDef.HWND hwnd = gameWindow();
        BaseTSD.LONG_PTR style = User32.INSTANCE.GetWindowLongPtr(hwnd, WinUser.GWL_STYLE);
        User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_STYLE, new Pointer(style.longValue() | WinUser.WS_CLIPCHILDREN));
    }

    /** Copies the bundled WebView2Loader.dll for this CPU next to the profile (once, or again when it changed). */
    private static Path extractLoader(Path dir) throws IOException {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch64") ? "arm64" : "x64";
        Path target = dir.resolve(arch).resolve("WebView2Loader.dll");
        try (InputStream in = WebView2Host.class.getResourceAsStream("/natives/webview2/" + arch + "/WebView2Loader.dll")) {
            if (in == null) {
                throw new IOException("WebView2Loader.dll for " + arch + " isn't in the mod jar");
            }
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(dir, "loader", ".dll");
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    private static Rect rect() {
        Rect rect = new Rect();
        rect.left = bounds[0];
        rect.top = bounds[1];
        rect.right = bounds[2];
        rect.bottom = bounds[3];
        return rect;
    }

    /** A Win32 RECT passed by value, as put_Bounds takes it. */
    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class Rect extends Structure implements Structure.ByValue {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }
}
