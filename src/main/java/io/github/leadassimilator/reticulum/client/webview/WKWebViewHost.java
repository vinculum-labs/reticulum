package io.github.leadassimilator.reticulum.client.webview;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFWNativeCocoa;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.Minecraft;

/**
 * The editor inside the game window on macOS: a WKWebView (Safari's engine, part of macOS) added as a subview of the
 * game window's content view, driven through the Objective-C runtime with JNA. Everything runs on the render thread,
 * which on macOS is the main thread AppKit requires.
 *
 * <p>Unlike WebView2 there is no load-finished callback without defining an Objective-C class at runtime, so the view
 * stays hidden until {@link #tick()} sees the first load finish.
 */
final class WKWebViewHost {
    private static final Function MSG_SEND;
    private static final Function GET_CLASS;
    private static final Function SELECTOR;

    static {
        NativeLibrary objc = NativeLibrary.getInstance("objc");
        MSG_SEND = objc.getFunction("objc_msgSend");
        GET_CLASS = objc.getFunction("objc_getClass");
        SELECTOR = objc.getFunction("sel_registerName");
        // Loads WebKit so its classes are registered with the runtime.
        NativeLibrary.getInstance("WebKit");
    }

    private static boolean failed;
    @Nullable
    private static Pointer webView;
    private static boolean visible;
    private static boolean loaded;
    /** Set when a page was asked for, until it has finished loading. */
    private static boolean loading;

    private WKWebViewHost() {
    }

    static boolean supported() {
        return !failed;
    }

    static boolean ready() {
        return webView != null;
    }

    static boolean shown() {
        return webView != null && loaded;
    }

    static void show(@Nullable String url, int x, int y, int width, int height) {
        try {
            if (webView == null) {
                create();
            }
            visible = true;
            setBounds(x, y, width, height);
            if (url != null) {
                load(url);
            }
            apply();
        } catch (RuntimeException | Error e) {
            Reticulum.LOGGER.warn("The in-game editor isn't available; using the browser instead", e);
            failed = true;
            Minecraft.getInstance().execute(EditorScreen::fallBackToBrowser);
        }
    }

    /** Positions the view; the arguments are window pixels from the top left, AppKit wants points from the bottom left. */
    static void setBounds(int x, int y, int width, int height) {
        if (webView == null) {
            return;
        }
        double scale = scale();
        double windowHeight = Minecraft.getInstance().getWindow().getHeight() / scale;
        Rect frame = new Rect();
        frame.x = x / scale;
        frame.width = width / scale;
        frame.height = height / scale;
        frame.y = windowHeight - y / scale - frame.height;
        msgVoid(webView, "setFrame:", frame);
    }

    static void hide() {
        visible = false;
        if (webView != null) {
            msgVoid(webView, "setHidden:", (byte) 1);
            focusGame();
        }
    }

    static void focusGame() {
        if (webView != null) {
            msg(window(), "makeFirstResponder:", msg(window(), "contentView"));
        }
    }

    static void close() {
        if (webView != null) {
            msgVoid(webView, "removeFromSuperview");
            msgVoid(webView, "release");
        }
        webView = null;
        visible = false;
        loaded = false;
        loading = false;
    }

    /** Reveals the view once its first page has loaded (checked every tick while the editor is open). */
    static void tick() {
        if (webView == null || loaded || !loading) {
            return;
        }
        boolean busy = (MSG_SEND.invokeInt(new Object[] {webView, selector("isLoading")}) & 0xFF) != 0;
        double progress = MSG_SEND.invokeDouble(new Object[] {webView, selector("estimatedProgress")});
        if (!busy && progress >= 1.0) {
            loaded = true;
            loading = false;
            apply();
        }
    }

    // ---------------------------------------------------------------- setup

    private static void create() {
        Pointer config = msg(msg(objcClass("WKWebViewConfiguration"), "alloc"), "init");
        Rect frame = new Rect();
        frame.width = 1;
        frame.height = 1;
        webView = msg(msg(objcClass("WKWebView"), "alloc"), "initWithFrame:configuration:", frame, config);
        msgVoid(config, "release");
        msgVoid(webView, "setHidden:", (byte) 1);
        msgVoid(msg(window(), "contentView"), "addSubview:", webView);
    }

    private static void load(String url) {
        Pointer nsUrl = msg(objcClass("NSURL"), "URLWithString:", nsString(url));
        Pointer request = msg(objcClass("NSURLRequest"), "requestWithURL:", nsUrl);
        msg(webView, "loadRequest:", request);
        loading = true;
        loaded = false;
    }

    private static void apply() {
        boolean show = visible && loaded;
        msgVoid(webView, "setHidden:", (byte) (show ? 0 : 1));
        if (show) {
            msg(window(), "makeFirstResponder:", webView);
        }
    }

    // ---------------------------------------------------------------- Objective-C plumbing

    private static Pointer window() {
        return new Pointer(GLFWNativeCocoa.glfwGetCocoaWindow(Minecraft.getInstance().getWindow().getWindow()));
    }

    private static double scale() {
        return MSG_SEND.invokeDouble(new Object[] {window(), selector("backingScaleFactor")});
    }

    private static Pointer objcClass(String name) {
        Pointer cls = GET_CLASS.invokePointer(new Object[] {name});
        if (cls == null) {
            throw new IllegalStateException("Objective-C class " + name + " not found");
        }
        return cls;
    }

    private static Pointer selector(String name) {
        return SELECTOR.invokePointer(new Object[] {name});
    }

    private static Object[] args(Pointer receiver, String selector, Object... args) {
        Object[] all = new Object[args.length + 2];
        all[0] = receiver;
        all[1] = selector(selector);
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
    }

    private static Pointer msg(Pointer receiver, String selector, Object... args) {
        return MSG_SEND.invokePointer(args(receiver, selector, args));
    }

    private static void msgVoid(Pointer receiver, String selector, Object... args) {
        MSG_SEND.invokeVoid(args(receiver, selector, args));
    }

    private static Pointer nsString(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return msg(objcClass("NSString"), "stringWithUTF8String:", memory);
    }

    /** CGRect / NSRect, passed by value: origin then size, as doubles on 64-bit macOS. */
    @Structure.FieldOrder({"x", "y", "width", "height"})
    public static class Rect extends Structure implements Structure.ByValue {
        public double x;
        public double y;
        public double width;
        public double height;
    }
}
