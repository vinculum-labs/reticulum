package io.github.leadassimilator.reticulum.client.webview;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;
import com.sun.jna.Callback;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The editor inside the game on Linux: WebKitGTK (the GTK 3 build, webkit2gtk-4.1, which most desktops already have)
 * in a GTK offscreen window that never shows. Each time WebKit repaints it, the view's paint is copied into an image
 * whose pixels the editor screen draws as a texture, so it works in fullscreen and under any window system. GTK runs on its own thread
 * with its own main loop; everything GTK is done there, and the game thread hands it work through GLib.
 */
final class WebKitGtkHost {
    private static final ResourceLocation FRAME = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "editor_view");

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
                Reticulum.LOGGER.warn("In-game editor: GTK work failed", e);
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

    static boolean ready() {
        return state == State.READY;
    }

    /** The page has loaded and a frame of it is there to draw. */
    static boolean shown() {
        return state == State.READY && loaded && glTexture != 0;
    }

    static void show(@Nullable String url, int x, int y, int w, int h) {
        visible = true;
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
        }, "Reticulum GTK");
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
            FrameTexture.register(FRAME, glTexture);
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
        return parts.length > 1 && parts[1].matches("[a-z]{2}") ? parts[0] + "-" + parts[1].toUpperCase(java.util.Locale.ROOT) : parts[0];
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
        Reticulum.LOGGER.warn("In-game editor: {}; using the browser instead", why, e);
        state = State.FAILED;
        if (visible) {
            Minecraft.getInstance().execute(EditorScreen::fallBackToBrowser);
        }
    }

    /**
     * The libraries, held for the whole session. JNA caches them weakly and unloads one (dlclose) once nothing refers
     * to it; WebKit's threads would then run code that is gone, and the game crashes (a garbage collection while a
     * world unloads did exactly that).
     */
    @Nullable
    private static volatile NativeLibrary gtk, webkit, glib, gobject, cairo;

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
        Reticulum.LOGGER.warn("In-game editor (WebKitGTK): loading {} failed: {}", uri, message);
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
