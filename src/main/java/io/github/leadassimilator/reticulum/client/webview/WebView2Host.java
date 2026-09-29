package io.github.leadassimilator.reticulum.client.webview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFWNativeWin32;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
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
import com.sun.jna.ptr.PointerByReference;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.Minecraft;

/**
 * The editor inside the game window on Windows: Edge's WebView2 as a child window over the game, driven through its
 * COM interfaces with JNA (both already part of Windows and Minecraft). Everything runs on the render thread, which
 * owns the game window; WebView2's callbacks arrive through the window's message loop, which GLFW pumps every frame.
 *
 * <p>Method numbers are the vtable slots from the SDK's WebView2.h (IUnknown takes 0-2).
 */
public final class WebView2Host {
    private static final int S_OK = 0;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    // ICoreWebView2Environment
    private static final int ENV_CREATE_CONTROLLER = 3;
    // ICoreWebView2Controller
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

    // COM callback objects, kept reachable for as long as native code may call them.
    @SuppressWarnings("unused")
    private static ComHandler environmentHandler;
    @SuppressWarnings("unused")
    private static ComHandler controllerHandler;
    @SuppressWarnings("unused")
    private static ComHandler navigationHandler;

    private WebView2Host() {
    }

    /** Windows with a WebView2 runtime we can reach, and nothing has failed yet. */
    public static boolean supported() {
        return Platform.isWindows() && state != State.FAILED;
    }

    public static boolean ready() {
        return state == State.READY;
    }

    /** Ready and the page has loaded, so the view is on screen when asked to be. */
    public static boolean shown() {
        return state == State.READY && loaded;
    }

    /** Shows the view over the game at these window pixels, loading {@code url} (null keeps the current page). */
    public static void show(@Nullable String url, int x, int y, int width, int height) {
        if (url != null) {
            pendingUrl = url;
        }
        visible = true;
        bounds = new int[] {x, y, x + Math.max(1, width), y + Math.max(1, height)};
        switch (state) {
            case NONE -> {
                create();
                if (state == State.FAILED) {
                    Minecraft.getInstance().execute(EditorScreen::fallBackToBrowser);
                }
            }
            case READY -> apply();
            default -> {
            }
        }
    }

    public static void setBounds(int x, int y, int width, int height) {
        bounds = new int[] {x, y, x + Math.max(1, width), y + Math.max(1, height)};
        if (state == State.READY) {
            call(controller, CTL_PUT_BOUNDS, rect());
        }
    }

    /** Hides the view and gives the keyboard back to the game; the page stays loaded (and connected) for next time. */
    public static void hide() {
        visible = false;
        if (state == State.READY) {
            call(controller, CTL_PUT_IS_VISIBLE, 0);
            User32.INSTANCE.SetFocus(gameWindow());
        }
    }

    /** Gives the keyboard to the game window (clicking the bar above the view), so the game's keys work there. */
    public static void focusGame() {
        if (Platform.isWindows()) {
            User32.INSTANCE.SetFocus(gameWindow());
        }
    }

    /** Closes the browser for good (leaving the world, or the game closing). */
    public static void close() {
        if (state == State.READY) {
            call(controller, CTL_CLOSE);
        }
        controller = null;
        webview = null;
        environment = null;
        state = State.NONE;
        visible = false;
        loaded = false;
    }

    // ---------------------------------------------------------------- creation

    private static void create() {
        state = State.CREATING;
        try {
            int hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue();
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) {
                throw new IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(hr));
            }
            clipChildren();
            // Absolute: the game folder can be relative ("." in development), and WebView2 would then look for its
            // folders beside javaw.exe, where it can't write.
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("reticulum");
            Path profile = Files.createDirectories(gameDir.resolve("webview2-profile"));
            NativeLibrary loader = NativeLibrary.getInstance(extractLoader(gameDir.resolve("webview2")).toString());
            environmentHandler = new ComHandler((Completed) (self, result, created) -> {
                if (result < 0 || created == null) {
                    fail("creating the WebView2 environment", result);
                    return S_OK;
                }
                addRef(created);
                environment = created;
                controllerHandler = new ComHandler((Completed) (self2, result2, made) -> {
                    onController(result2, made);
                    return S_OK;
                });
                int r = call(environment, ENV_CREATE_CONTROLLER, gameWindow().getPointer(), controllerHandler.pointer());
                if (r < 0) {
                    fail("creating the WebView2 controller", r);
                }
                return S_OK;
            });
            Function createEnvironment = loader.getFunction("CreateCoreWebView2EnvironmentWithOptions");
            // The profile (cookies, so the editor stays logged in) lives in the game folder, not beside javaw.exe.
            int r = createEnvironment.invokeInt(new Object[] {null, new WString(profile.toString()), null,
                    environmentHandler.pointer()});
            if (r < 0) {
                fail("starting WebView2 (is the WebView2 runtime installed?)", r);
            }
        } catch (RuntimeException | Error | IOException e) {
            Reticulum.LOGGER.warn("The in-game editor isn't available; using the browser instead", e);
            state = State.FAILED;
        }
    }

    private static void onController(int result, @Nullable Pointer made) {
        if (result < 0 || made == null) {
            fail("creating the WebView2 controller", result);
            return;
        }
        addRef(made);
        controller = made;
        paintBehindPage();
        PointerByReference core = new PointerByReference();
        call(controller, CTL_GET_CORE_WEBVIEW2, core);
        webview = core.getValue();
        navigationHandler = new ComHandler((Event) (self, sender, args) -> {
            if (!loaded) {
                loaded = true;
                apply();
            }
            return S_OK;
        });
        call(webview, WV_ADD_NAVIGATION_COMPLETED, navigationHandler.pointer(), new Memory(8));
        state = State.READY;
        apply();
    }

    /** Brings the view in line with what was asked for: page, bounds, visibility and focus. */
    private static void apply() {
        // GLFW rebuilds the window's style when it enters or leaves fullscreen, dropping WS_CLIPCHILDREN.
        clipChildren();
        if (pendingUrl != null) {
            call(webview, WV_NAVIGATE, new WString(pendingUrl));
            pendingUrl = null;
        }
        call(controller, CTL_PUT_BOUNDS, rect());
        call(controller, CTL_PUT_IS_VISIBLE, visible && loaded ? 1 : 0);
        if (visible && loaded) {
            // COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC
            call(controller, CTL_MOVE_FOCUS, 0);
        }
    }

    /**
     * WebView2 fills the view with white before the page is composited; make that the placeholder's dark color, so
     * showing the view never flashes white.
     */
    private static void paintBehindPage() {
        PointerByReference controller2 = new PointerByReference();
        com.sun.jna.platform.win32.Guid.GUID iid = new com.sun.jna.platform.win32.Guid.GUID(IID_CONTROLLER2);
        if (call(controller, 0, iid, controller2) < 0 || controller2.getValue() == null) {
            return;
        }
        Color color = new Color();
        color.a = (byte) 0xFF;
        color.r = (byte) 0x12;
        color.g = (byte) 0x15;
        color.b = (byte) 0x1B;
        call(controller2.getValue(), CTL2_PUT_DEFAULT_BACKGROUND_COLOR, color);
        call(controller2.getValue(), 2);
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

    /** Calls a COM method by its vtable slot; returns its HRESULT. */
    private static int call(@Nullable Pointer self, int slot, Object... args) {
        if (self == null) {
            return -1;
        }
        Pointer function = self.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return Function.getFunction(function).invokeInt(all);
    }

    private static void addRef(Pointer self) {
        call(self, 1);
    }

    // ---------------------------------------------------------------- COM callback objects

    /** The Invoke of a WebView2 "completed" handler: (this, HRESULT, the created object). */
    interface Completed extends Callback {
        int invoke(Pointer self, int result, Pointer created);
    }

    /** The Invoke of a WebView2 event handler: (this, sender, event args). */
    interface Event extends Callback {
        int invoke(Pointer self, Pointer sender, Pointer args);
    }

    interface QueryInterface extends Callback {
        int invoke(Pointer self, Pointer iid, Pointer out);
    }

    interface RefCount extends Callback {
        int invoke(Pointer self);
    }

    /**
     * A minimal COM object for a completed handler: a vtable of QueryInterface, AddRef, Release and Invoke. Its
     * lifetime is ours (a static field), so the reference count is ignored.
     */
    static final class ComHandler {
        private final Memory object = new Memory(Native.POINTER_SIZE);
        private final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
        private final QueryInterface queryInterface = (self, iid, out) -> {
            out.setPointer(0, self);
            return S_OK;
        };
        private final RefCount addRef = self -> 1;
        private final RefCount release = self -> 1;
        private final Callback invoke;

        ComHandler(Callback invoke) {
            this.invoke = invoke;
            vtable.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(invoke));
            object.setPointer(0, vtable);
        }

        Pointer pointer() {
            return object;
        }
    }
}
