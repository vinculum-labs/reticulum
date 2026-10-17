package io.github.leadassimilator.telispex.client.view;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.sun.jna.Callback;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The editor inside the game on Linux: WebKitGTK (the GTK 3 build, webkit2gtk-4.1, which most desktops already have)
 * in a GTK offscreen window that never shows. Each time WebKit repaints it, the view's paint is copied into an image
 * whose pixels the view screen draws as a texture, so it works in fullscreen and under any window system. GTK runs on its own thread
 * with its own main loop; everything GTK is done there, and the game thread hands it work through GLib.
 */
final class WebKitGtkHost {
    private static final ResourceLocation FRAME = ResourceLocation.fromNamespaceAndPath(Telispex.ID, "view");

    /** WebKitHardwareAccelerationPolicy NEVER: an offscreen window can only be read back when WebKit paints in software. */
    private static final int ACCELERATION_NEVER = 2;
    /** WebKitLoadEvent FINISHED. */
    private static final int LOAD_FINISHED = 3;

    private enum State { NONE, CREATING, READY, FAILED }

    private static volatile State state = State.NONE;
    private static volatile boolean loaded;
    private static boolean visible;
    private static int width = 1;
    private static int height = 1;

    // GTK objects: touched only on the GTK thread.
    @Nullable
    private static Pointer window;
    @Nullable
    private static Pointer view;
    private static boolean grabQueued;
    /** The view is loading the blank page of a closed editor. */
    private static boolean blanking;

    /** Work for the GTK thread, run from a GLib idle callback. */
    private static final ConcurrentLinkedQueue<Runnable> WORK = new ConcurrentLinkedQueue<>();

    /** The latest frame's pixels (BGRA), swapped in by the GTK thread and taken by the render thread. */
    private static final Object FRAME_LOCK = new Object();
    @Nullable
    private static ByteBuffer pixels;
    private static int pixelsWidth;
    private static int pixelsHeight;
    private static boolean fresh;
    // The texture: touched only on the render thread.
    private static int glTexture;
    private static int textureWidth;
    private static int textureHeight;

    // Callbacks GTK and GLib hold: kept for the session, so their code outlives any call.
    private static final IdleFunction RUN_WORK = data -> {
        Runnable job;
        while ((job = WORK.poll()) != null) {
            try {
                job.run();
            } catch (RuntimeException e) {
                Telispex.LOGGER.warn("In-game view: GTK work failed", e);
            }
        }
        return 0; // G_SOURCE_REMOVE
    };
    /** Once GTK is idle after a repaint (a burst of repaints is one copy): the page as WebKit painted it. */
    private static final IdleFunction GRAB = data -> {
        grabQueued = false;
        if (visible && loaded) {
            grab();
        }
        return 0; // G_SOURCE_REMOVE
    };
    /** WebKit repainted the window: copy it out (not before the page has loaded; see {@link #queueGrab}). */
    private static final DamageHandler ON_DAMAGE = (widget, event, data) -> {
        queueGrab();
        return 0; // FALSE: let others see it too
    };
    private static final LoadHandler ON_LOAD = (webView, event, data) -> {
        if (event == LOAD_FINISHED && blanking) {
            // The blank page of a closed editor: nothing to show.
            blanking = false;
        } else if (event == LOAD_FINISHED) {
            loaded = true;
            // The page may have painted already; don't wait for the next repaint for the first frame.
            queueGrab();
        }
    };

    private WebKitGtkHost() {
    }

    /** Linux with WebKitGTK installed, and nothing has failed yet. */
    static boolean supported() {
        return Platform.isLinux() && state != State.FAILED;
    }

    /** Ready with the editor in it: after close() it holds a blank page, so the next open loads the editor again. */
    static boolean ready() {
        return state == State.READY && !closed;
    }

    /** Closed (left the world) and not given a page since. */
    private static volatile boolean closed;

    /** The page has loaded and a frame of it is there to draw. */
    static boolean shown() {
        return state == State.READY && loaded && glTexture != 0;
    }

    static void show(@Nullable String url, int x, int y, int w, int h) {
        visible = true;
        if (url != null) {
            closed = false;
        }
        width = Math.max(1, w);
        height = Math.max(1, h);
        int newWidth = width;
        int newHeight = height;
        if (state == State.NONE) {
            state = State.CREATING;
            start(url, newWidth, newHeight);
            return;
        }
        post(() -> {
            resize(newWidth, newHeight);
            if (url != null) {
                // A blank page still loading is replaced by this one, whose load is the one that counts.
                blanking = false;
                webkit().getFunction("webkit_web_view_load_uri").invokeVoid(new Object[] {view, url});
            }
        });
    }

    static void hide() {
        visible = false;
    }

    /**
     * Closes the page (leaving the world): the view goes to a blank page, dropping the editor and its connection, and
     * loads the next world's editor when shown again. The view itself is kept: destroying it mid-session tears WebKit
     * down while its threads still run, and the game crashes.
     */
    static void close() {
        visible = false;
        loaded = false;
        closed = true;
        if (state == State.READY || state == State.CREATING) {
            post(() -> {
                if (view != null) {
                    blanking = true;
                    webkit().getFunction("webkit_web_view_load_uri").invokeVoid(new Object[] {view, "about:blank"});
                }
            });
        }
        if (glTexture != 0) {
            FrameTexture.unregister(FRAME);
            GlStateManager._deleteTexture(glTexture);
            glTexture = 0;
            // A new texture gets its storage (and is registered) on the next upload.
            textureWidth = 0;
            textureHeight = 0;
        }
        synchronized (FRAME_LOCK) {
            pixels = null;
            fresh = false;
        }
    }

    /** The latest frame, uploaded to the texture when a newer one arrived; null before the first. */
    @Nullable
    static ResourceLocation frame() {
        if (state != State.READY || !loaded) {
            return null;
        }
        synchronized (FRAME_LOCK) {
            if (fresh && pixels != null) {
                upload(pixels, pixelsWidth, pixelsHeight);
                fresh = false;
            }
        }
        return glTexture != 0 ? FRAME : null;
    }

    static int frameWidth() {
        return textureWidth;
    }

    static int frameHeight() {
        return textureHeight;
    }

    /**
     * The game is closing: stops GTK's loop and waits (briefly) for its thread to finish, so nothing of GTK's runs
     * while the process exits.
     */
    static void shutdown() {
        if (gtkThread == null) {
            return;
        }
        // Only GTK's loop stops; nothing is destroyed (that crashes WebKit, see close), the process ending frees it.
        post(() -> gtk().getFunction("gtk_main_quit").invokeVoid(new Object[0]));
        try {
            gtkThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gtkThread = null;
        state = State.FAILED;
    }

    // ---------------------------------------------------------------- input

    // GdkEventType and the other GDK values used below (gdkevents.h, gdktypes.h).
    private static final int MOTION_NOTIFY = 3;
    private static final int BUTTON_PRESS = 4;
    private static final int BUTTON_RELEASE = 7;
    private static final int KEY_PRESS = 8;
    private static final int KEY_RELEASE = 9;
    private static final int ENTER_NOTIFY = 10;
    private static final int LEAVE_NOTIFY = 11;
    private static final int FOCUS_CHANGE = 12;
    private static final int SCROLL = 31;
    private static final int SCROLL_SMOOTH = 4;
    /** GDK_BUTTON1_MASK, BUTTON2 (middle) and BUTTON3 (right), by GLFW button. */
    private static final int[] BUTTON_MASK = {1 << 8, 1 << 10, 1 << 9, 0, 0};
    /** GDK button numbers by GLFW button: left, right, middle, back, forward. */
    private static final int[] GDK_BUTTON = {1, 3, 2, 8, 9};

    /** The page has the keyboard: the game passes it keys (it has no window of its own to take them). */
    private static volatile boolean keyboard;
    // On the GTK thread: what the view was last told.
    private static boolean pointerInside;
    private static int heldMask;
    /** The modifier keys down now (GDK mask), read on the game's thread before each mouse event. */
    private static volatile int modifierMask;

    /** Whether the game draws the view now, so the screen passes it the mouse. */
    static boolean drawnByGame() {
        return state == State.READY && loaded;
    }

    static boolean hasKeyboard() {
        return keyboard && drawnByGame();
    }

    static void mouseMove(int x, int y) {
        readModifiers();
        post(() -> {
            Pointer target = viewWindow();
            if (target == null) {
                return;
            }
            if (!pointerInside) {
                pointerInside = true;
                crossing(ENTER_NOTIFY, target, x, y);
            }
            Pointer event = event(MOTION_NOTIFY, target);
            event.setDouble(24, x);
            event.setDouble(32, y);
            event.setInt(48, heldMask | modifierMask);
            event.setDouble(64, x);
            event.setDouble(72, y);
            dispatch(event, pointer());
            cursorShape = cursorShape(target);
        });
    }

    /** The GLFW cursor shape the page asks for (0 for the default arrow), as of the last mouse move. */
    static int cursor() {
        return drawnByGame() ? cursorShape : 0;
    }

    private static volatile int cursorShape;
    /** Cursors by the names WebKit asks GTK for (CSS cursor names, and older X ones), to the GLFW shapes they show as. */
    private static final String[][] CURSORS = {
        {"text", "vertical-text", "xterm"},
        {"pointer", "hand2", "hand1"},
        {"crosshair", "cell", "cross"},
        {"ew-resize", "col-resize", "e-resize", "w-resize", "sb_h_double_arrow"},
        {"ns-resize", "row-resize", "n-resize", "s-resize", "sb_v_double_arrow"},
        {"nwse-resize", "nw-resize", "se-resize"},
        {"nesw-resize", "ne-resize", "sw-resize"},
        {"move", "all-scroll", "fleur"},
        {"not-allowed", "no-drop", "crossed_circle"},
    };
    private static final int[] CURSOR_SHAPES = {GLFW.GLFW_IBEAM_CURSOR, GLFW.GLFW_POINTING_HAND_CURSOR, GLFW.GLFW_CROSSHAIR_CURSOR,
        GLFW.GLFW_RESIZE_EW_CURSOR, GLFW.GLFW_RESIZE_NS_CURSOR, GLFW.GLFW_RESIZE_NWSE_CURSOR, GLFW.GLFW_RESIZE_NESW_CURSOR,
        GLFW.GLFW_RESIZE_ALL_CURSOR, GLFW.GLFW_NOT_ALLOWED_CURSOR};
    /** GTK's cursor objects for those names, made once; GTK keeps one per name, so WebKit's is the same object. */
    @Nullable
    private static Map<Pointer, Integer> knownCursors;

    /** On the GTK thread: the shape of the cursor WebKit set on the view's window. */
    private static int cursorShape(Pointer viewWindow) {
        if (knownCursors == null) {
            knownCursors = new HashMap<>();
            for (int i = 0; i < CURSORS.length; i++) {
                for (String name : CURSORS[i]) {
                    Pointer cursor = gdk().getFunction("gdk_cursor_new_from_name").invokePointer(new Object[] {display(), name});
                    // Kept (never unreferenced), so GTK keeps the same object for the name.
                    if (cursor != null) {
                        knownCursors.putIfAbsent(cursor, CURSOR_SHAPES[i]);
                    }
                }
            }
        }
        Pointer current = gdk().getFunction("gdk_window_get_cursor").invokePointer(new Object[] {viewWindow});
        return current == null ? 0 : knownCursors.getOrDefault(current, 0);
    }

    static void mouseLeave() {
        post(() -> {
            Pointer target = viewWindow();
            if (target != null && pointerInside) {
                pointerInside = false;
                crossing(LEAVE_NOTIFY, target, -1, -1);
            }
        });
    }

    /** A button (GLFW numbering) down or up; WebKit counts double clicks itself from their times and places. */
    static void button(int button, boolean down, int x, int y) {
        if (button < 0 || button >= GDK_BUTTON.length) {
            return;
        }
        readModifiers();
        post(() -> {
            Pointer target = viewWindow();
            if (target == null) {
                return;
            }
            Pointer event = event(down ? BUTTON_PRESS : BUTTON_RELEASE, target);
            event.setDouble(24, x);
            event.setDouble(32, y);
            // The state is the buttons held before this event, as X reports it.
            event.setInt(48, heldMask | modifierMask);
            event.setInt(52, GDK_BUTTON[button]);
            event.setDouble(64, x);
            event.setDouble(72, y);
            heldMask = down ? heldMask | BUTTON_MASK[button] : heldMask & ~BUTTON_MASK[button];
            dispatch(event, pointer());
            cursorShape = cursorShape(target);
        });
    }

    /**
     * GLFW's wheel (positive y up, positive x left). Whole notches go as a wheel's discrete steps, one event each;
     * WebKit's smooth scrolling (meant for touchpads) swallowed the first notch after the direction changed.
     * Fractions (touchpads, high-resolution wheels) go as smooth scrolling (positive y down, positive x right).
     */
    static void wheel(double scrollX, double scrollY, int x, int y) {
        readModifiers();
        post(() -> {
            Pointer target = viewWindow();
            if (target == null) {
                return;
            }
            boolean whole = scrollX == Math.rint(scrollX) && scrollY == Math.rint(scrollY);
            if (whole) {
                // GdkScrollDirection: UP, DOWN, LEFT, RIGHT.
                for (int i = 0; i < Math.abs((int) scrollY); i++) {
                    scroll(target, scrollY > 0 ? 0 : 1, 0, 0, x, y);
                }
                for (int i = 0; i < Math.abs((int) scrollX); i++) {
                    scroll(target, scrollX > 0 ? 2 : 3, 0, 0, x, y);
                }
            } else {
                scroll(target, SCROLL_SMOOTH, -scrollX, -scrollY, x, y);
            }
        });
    }

    private static void scroll(Pointer target, int direction, double deltaX, double deltaY, int x, int y) {
        Pointer event = event(SCROLL, target);
        event.setDouble(24, x);
        event.setDouble(32, y);
        event.setInt(40, heldMask | modifierMask);
        event.setInt(44, direction);
        event.setDouble(56, x);
        event.setDouble(64, y);
        event.setDouble(72, deltaX);
        event.setDouble(80, deltaY);
        dispatch(event, pointer());
    }

    /** Gives the page the keyboard (a click in it) or takes it back (a click on the bar). */
    static void focus(boolean in) {
        if (keyboard == in) {
            return;
        }
        keyboard = in;
        post(() -> {
            Pointer target = topWindow();
            if (target == null) {
                return;
            }
            if (in) {
                gtk().getFunction("gtk_widget_grab_focus").invokeVoid(new Object[] {view});
            }
            Pointer event = event(FOCUS_CHANGE, target);
            event.setShort(18, (short) (in ? 1 : 0));
            dispatch(event, keyboardDevice());
        });
    }

    /**
     * A key down or up. The key itself comes from GLFW's scancode, the system's own key code, so the keyboard layout
     * and dead keys are GTK's to work out exactly as for any of its windows; WebKit's input method makes the text.
     */
    static void key(int scancode, int modifiers, boolean down) {
        // GDK's key codes are X's: GLFW gives X key codes on X11 and evdev codes (8 lower) on Wayland.
        int keycode = scancode + (glfwOnWayland() ? 8 : 0);
        int state = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 1 : 0;
        state |= (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 ? 1 << 2 : 0;
        state |= (modifiers & GLFW.GLFW_MOD_ALT) != 0 ? 1 << 3 : 0;
        state |= (modifiers & GLFW.GLFW_MOD_SUPER) != 0 ? 1 << 26 : 0;
        int mask = state;
        post(() -> {
            Pointer target = topWindow();
            if (target == null) {
                return;
            }
            Pointer keymap = gdk().getFunction("gdk_keymap_get_for_display").invokePointer(new Object[] {display()});
            IntByReference keyval = new IntByReference();
            if (gdk().getFunction("gdk_keymap_translate_keyboard_state").invokeInt(new Object[] {keymap, keycode, mask, 0, keyval,
                    null, null, null}) == 0) {
                return;
            }
            Pointer event = event(down ? KEY_PRESS : KEY_RELEASE, target);
            event.setInt(24, mask);
            event.setInt(28, keyval.getValue());
            event.setShort(48, (short) keycode);
            dispatch(event, keyboardDevice());
        });
    }

    /**
     * The modifier keys held right now, from GLFW. Not from the last key event: those carry the keys held before them
     * (X's way), so releasing Ctrl reported Ctrl as held, and every mouse event after claimed it was.
     */
    private static void readModifiers() {
        long window = Minecraft.getInstance().getWindow().handle();
        int mask = down(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0;
        mask |= down(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL) ? 1 << 2 : 0;
        mask |= down(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT) ? 1 << 3 : 0;
        mask |= down(window, GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER) ? 1 << 26 : 0;
        modifierMask = mask;
    }

    private static boolean down(long window, int left, int right) {
        return GLFW.glfwGetKey(window, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, right) == GLFW.GLFW_PRESS;
    }

    private static boolean glfwOnWayland() {
        try {
            return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** A new GDK event of {@code type} for {@code window}: sent by us, stamped now. */
    private static Pointer event(int type, Pointer window) {
        Pointer event = gdk().getFunction("gdk_event_new").invokePointer(new Object[] {type});
        // The event holds a reference to its window, let go of by gdk_event_free.
        event.setPointer(8, gobject().getFunction("g_object_ref").invokePointer(new Object[] {window}));
        event.setByte(16, (byte) 1);
        if (type != FOCUS_CHANGE) {
            event.setInt(type == ENTER_NOTIFY || type == LEAVE_NOTIFY ? 32 : 20, (int) (System.nanoTime() / 1_000_000));
        }
        return event;
    }

    /** GdkEventCrossing: the pointer entering or leaving the view. */
    private static void crossing(int type, Pointer window, int x, int y) {
        Pointer event = event(type, window);
        event.setDouble(40, x);
        event.setDouble(48, y);
        event.setDouble(56, x);
        event.setDouble(64, y);
        event.setInt(84, heldMask | modifierMask);
        dispatch(event, pointer());
    }

    /** Hands the event to GTK as if it had come from the window system, then frees it. */
    private static void dispatch(Pointer event, @Nullable Pointer device) {
        if (device != null) {
            gdk().getFunction("gdk_event_set_device").invokeVoid(new Object[] {event, device});
        }
        gtk().getFunction("gtk_main_do_event").invokeVoid(new Object[] {event});
        gdk().getFunction("gdk_event_free").invokeVoid(new Object[] {event});
    }

    /** The view's own GDK window (where pointer events go); null before it exists. */
    @Nullable
    private static Pointer viewWindow() {
        return view == null ? null : gtk().getFunction("gtk_widget_get_window").invokePointer(new Object[] {view});
    }

    /** The offscreen window's GDK window (where keyboard and focus events go). */
    @Nullable
    private static Pointer topWindow() {
        return window == null ? null : gtk().getFunction("gtk_widget_get_window").invokePointer(new Object[] {window});
    }

    private static Pointer display() {
        return gdk().getFunction("gdk_display_get_default").invokePointer(new Object[0]);
    }

    @Nullable
    private static Pointer pointer() {
        Pointer seat = gdk().getFunction("gdk_display_get_default_seat").invokePointer(new Object[] {display()});
        return seat == null ? null : gdk().getFunction("gdk_seat_get_pointer").invokePointer(new Object[] {seat});
    }

    @Nullable
    private static Pointer keyboardDevice() {
        Pointer seat = gdk().getFunction("gdk_display_get_default_seat").invokePointer(new Object[] {display()});
        return seat == null ? null : gdk().getFunction("gdk_seat_get_keyboard").invokePointer(new Object[] {seat});
    }

    // ---------------------------------------------------------------- the GTK thread

    private static boolean gtkStarted;
    @Nullable
    private static Thread gtkThread;

    private static void start(@Nullable String url, int w, int h) {
        // The game's language ("en_us") as a BCP 47 tag ("en-US"), read here on the render thread.
        String language = toLanguageTag(Minecraft.getInstance().getLanguageManager().getSelected());
        if (gtkStarted) {
            post(() -> create(url, w, h));
            return;
        }
        gtkStarted = true;
        Thread thread = new Thread(() -> {
            try {
                // GTK would otherwise switch the whole process to the user's locale, and the game's graphics driver
                // can then misread numbers in shaders (a comma for the decimal point).
                gtk().getFunction("gtk_disable_setlocale").invokeVoid(new Object[0]);
                if (gtk().getFunction("gtk_init_check").invokeInt(new Object[] {null, null}) == 0) {
                    fail("GTK couldn't start (no display?)", null);
                    return;
                }
                // The page's language, before the web process starts. Without it WebKit takes it from the process
                // locale, which stays "C" (see above): not a language tag, so scripts that format numbers with
                // navigator.language (the editor's charts do) throw and the page never draws.
                Pointer context = webkit().getFunction("webkit_web_context_get_default").invokePointer(new Object[0]);
                // Held for good: WebKit's own reference is dropped by a static destructor as the process exits, and
                // tearing the context down then, on the exiting thread rather than this one, fails WebKit's main
                // thread check and aborts the exit (code 134). With ours outstanding it is never torn down.
                gobject().getFunction("g_object_ref").invokePointer(new Object[] {context});
                webkit().getFunction("webkit_web_context_set_preferred_languages").invokeVoid(new Object[] {context,
                        new StringArray(new String[] {language})});
                create(url, w, h);
                gtk().getFunction("gtk_main").invokeVoid(new Object[0]);
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                fail("WebKitGTK isn't available (install libwebkit2gtk-4.1)", e);
            }
        }, "Telispex GTK");
        thread.setDaemon(true);
        thread.start();
        gtkThread = thread;
    }

    /** On the GTK thread: the offscreen window and its web view, loading {@code url}. */
    private static void create(@Nullable String url, int w, int h) {
        window = gtk().getFunction("gtk_offscreen_window_new").invokePointer(new Object[0]);
        view = webkit().getFunction("webkit_web_view_new").invokePointer(new Object[0]);
        Pointer settings = webkit().getFunction("webkit_web_view_get_settings").invokePointer(new Object[] {view});
        webkit().getFunction("webkit_settings_set_hardware_acceleration_policy").invokeVoid(new Object[] {settings, ACCELERATION_NEVER});
        Rgba background = new Rgba();
        background.red = (EmbeddedView.BACKGROUND >> 16 & 0xFF) / 255.0;
        background.green = (EmbeddedView.BACKGROUND >> 8 & 0xFF) / 255.0;
        background.blue = (EmbeddedView.BACKGROUND & 0xFF) / 255.0;
        background.alpha = 1;
        webkit().getFunction("webkit_web_view_set_background_color").invokeVoid(new Object[] {view, background});
        gtk().getFunction("gtk_container_add").invokeVoid(new Object[] {window, view});
        connect(window, "damage-event", ON_DAMAGE);
        connect(view, "load-changed", ON_LOAD);
        connect(view, "load-failed", ON_LOAD_FAILED);
        resize(w, h);
        gtk().getFunction("gtk_widget_show_all").invokeVoid(new Object[] {window});
        if (url != null) {
            webkit().getFunction("webkit_web_view_load_uri").invokeVoid(new Object[] {view, url});
        }
        state = State.READY;
    }

    private static void resize(int w, int h) {
        if (window == null) {
            return;
        }
        gtk().getFunction("gtk_widget_set_size_request").invokeVoid(new Object[] {view, w, h});
        gtk().getFunction("gtk_window_resize").invokeVoid(new Object[] {window, w, h});
    }

    /**
     * On the GTK thread: copies the page out once GTK is idle. Not before it has loaded: asked to draw before it has
     * painted, WebKit waits for the web process, which held up the load itself when it was asked often.
     */
    private static void queueGrab() {
        if (loaded && !grabQueued) {
            grabQueued = true;
            glib().getFunction("g_idle_add").invokeInt(new Object[] {GRAB, null});
        }
    }

    /**
     * On the GTK thread: has the web view draw what WebKit last painted into an image of our own, and keeps that as the
     * frame the render thread draws. (Cheap: WebKit only copies its own buffer; the page isn't rendered again.)
     */
    private static void grab() {
        if (view == null) {
            return;
        }
        int w = gtk().getFunction("gtk_widget_get_allocated_width").invokeInt(new Object[] {view});
        int h = gtk().getFunction("gtk_widget_get_allocated_height").invokeInt(new Object[] {view});
        if (w <= 1 || h <= 1) {
            return;
        }
        Pointer surface = cairo().getFunction("cairo_image_surface_create").invokePointer(new Object[] {0, w, h}); // ARGB32
        Pointer cr = cairo().getFunction("cairo_create").invokePointer(new Object[] {surface});
        try {
            gtk().getFunction("gtk_widget_draw").invokeVoid(new Object[] {view, cr});
            keep(surface);
        } finally {
            cairo().getFunction("cairo_destroy").invokeVoid(new Object[] {cr});
            cairo().getFunction("cairo_surface_destroy").invokeVoid(new Object[] {surface});
        }
    }

    /** Copies an image surface (CAIRO_FORMAT_ARGB32) into the frame the render thread draws. */
    private static void keep(Pointer surface) {
        cairo().getFunction("cairo_surface_flush").invokeVoid(new Object[] {surface});
        int w = cairo().getFunction("cairo_image_surface_get_width").invokeInt(new Object[] {surface});
        int h = cairo().getFunction("cairo_image_surface_get_height").invokeInt(new Object[] {surface});
        int stride = cairo().getFunction("cairo_image_surface_get_stride").invokeInt(new Object[] {surface});
        Pointer data = cairo().getFunction("cairo_image_surface_get_data").invokePointer(new Object[] {surface});
        if (w <= 0 || h <= 0 || data == null) {
            return;
        }
        // Per pixel B, G, R, A in memory on little-endian machines, which OpenGL takes as BGRA.
        ByteBuffer frame = BufferUtils.createByteBuffer(w * h * 4);
        if (stride == w * 4) {
            frame.put(data.getByteBuffer(0, (long) w * h * 4));
        } else {
            for (int y = 0; y < h; y++) {
                frame.put(data.getByteBuffer((long) y * stride, w * 4L));
            }
        }
        frame.flip();
        synchronized (FRAME_LOCK) {
            pixels = frame;
            pixelsWidth = w;
            pixelsHeight = h;
            fresh = true;
        }
    }

    // ---------------------------------------------------------------- the render thread

    private static void upload(ByteBuffer frame, int w, int h) {
        if (glTexture == 0) {
            glTexture = GlStateManager._genTexture();
        }
        GlStateManager._bindTexture(glTexture);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        if (w != textureWidth || h != textureHeight) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, frame);
            textureWidth = w;
            textureHeight = h;
            FrameTexture.register(FRAME, glTexture, w, h);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, frame);
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** A game language code ("en_us", "pt_br") as a BCP 47 tag ("en-US", "pt-BR"); "en-US" for anything odd. */
    static String toLanguageTag(String code) {
        String[] parts = code.split("_");
        if (parts.length == 0 || !parts[0].matches("[a-z]{2,3}")) {
            return "en-US";
        }
        return parts.length > 1 && parts[1].matches("[a-z]{2}") ? parts[0] + "-" + parts[1].toUpperCase(Locale.ROOT) : parts[0];
    }

    /** Runs {@code job} on the GTK thread. */
    private static void post(Runnable job) {
        WORK.add(job);
        glib().getFunction("g_idle_add").invokeInt(new Object[] {RUN_WORK, null});
    }

    private static void connect(Pointer instance, String signal, Callback handler) {
        gobject().getFunction("g_signal_connect_data").invokeLong(new Object[] {instance, signal, handler, null, null, 0});
    }

    private static void fail(String why, @Nullable Throwable e) {
        Telispex.LOGGER.warn("In-game view: {}; using the browser instead", why, e);
        state = State.FAILED;
        if (visible) {
            Minecraft.getInstance().execute(ViewScreen::fallBackToBrowser);
        }
    }

    /**
     * The libraries, held for the whole session. JNA caches them weakly and unloads one (dlclose) once nothing refers
     * to it; WebKit's threads would then run code that is gone, and the game crashes (a garbage collection while a
     * world unloads did exactly that).
     */
    @Nullable
    private static volatile NativeLibrary gtk, gdk, webkit, glib, gobject, cairo;

    private static NativeLibrary gdk() {
        NativeLibrary library = gdk;
        return library != null ? library : (gdk = NativeLibrary.getInstance("libgdk-3.so.0"));
    }

    private static NativeLibrary gtk() {
        NativeLibrary library = gtk;
        return library != null ? library : (gtk = NativeLibrary.getInstance("libgtk-3.so.0"));
    }

    private static NativeLibrary webkit() {
        NativeLibrary library = webkit;
        return library != null ? library : (webkit = NativeLibrary.getInstance("libwebkit2gtk-4.1.so.0"));
    }

    private static NativeLibrary glib() {
        NativeLibrary library = glib;
        return library != null ? library : (glib = NativeLibrary.getInstance("libglib-2.0.so.0"));
    }

    private static NativeLibrary gobject() {
        NativeLibrary library = gobject;
        return library != null ? library : (gobject = NativeLibrary.getInstance("libgobject-2.0.so.0"));
    }

    private static NativeLibrary cairo() {
        NativeLibrary library = cairo;
        return library != null ? library : (cairo = NativeLibrary.getInstance("libcairo.so.2"));
    }

    /** GSourceFunc: returns whether to keep the source. */
    interface IdleFunction extends Callback {
        int invoke(Pointer data);
    }

    /** GtkWidget::damage-event: (widget, event, data), returns whether it was handled. */
    interface DamageHandler extends Callback {
        int invoke(Pointer widget, Pointer event, Pointer data);
    }

    /** WebKitWebView::load-changed: (web view, load event, data). */
    interface LoadHandler extends Callback {
        void invoke(Pointer view, int event, Pointer data);
    }

    /** WebKitWebView::load-failed: (web view, load event, failing URI, GError, data), returns whether it was handled. */
    interface LoadFailedHandler extends Callback {
        int invoke(Pointer view, int event, String uri, Pointer error, Pointer data);
    }

    private static final LoadFailedHandler ON_LOAD_FAILED = (webView, event, uri, error, data) -> {
        // GError: domain (quark), code, message.
        String message = error == null ? "?" : error.getPointer(8).getString(0);
        Telispex.LOGGER.warn("In-game view (WebKitGTK): loading {} failed: {}", uri, message);
        return 0;
    };

    /** GdkRGBA. */
    @Structure.FieldOrder({"red", "green", "blue", "alpha"})
    public static class Rgba extends Structure {
        public double red;
        public double green;
        public double blue;
        public double alpha;
    }
}
