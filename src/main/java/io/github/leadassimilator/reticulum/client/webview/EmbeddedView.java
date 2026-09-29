package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import com.sun.jna.Platform;

/**
 * The editor's web view inside the game window, whichever the system provides: WebView2 on Windows, WKWebView on
 * macOS. Elsewhere (Linux) there is none, and the editor opens in the browser.
 */
public final class EmbeddedView {
    private EmbeddedView() {
    }

    public static boolean supported() {
        if (Platform.isWindows()) {
            return WebView2Host.supported();
        }
        return Platform.isMac() && WKWebViewHost.supported();
    }

    /** The view exists (its page may still be loading). */
    public static boolean ready() {
        if (Platform.isWindows()) {
            return WebView2Host.ready();
        }
        return Platform.isMac() && WKWebViewHost.ready();
    }

    /** The page has loaded, so the view is on screen when asked to be. */
    public static boolean shown() {
        if (Platform.isWindows()) {
            return WebView2Host.shown();
        }
        return Platform.isMac() && WKWebViewHost.shown();
    }

    /**
     * Whether the game's fullscreen hides child views. Exclusive fullscreen on Windows bypasses the compositor; macOS
     * fullscreen is still composited, so the view shows there.
     */
    public static boolean hiddenByFullscreen() {
        return Platform.isWindows();
    }

    /** Shows the view at these window pixels (from the top left), loading {@code url} unless it is null. */
    public static void show(@Nullable String url, int x, int y, int width, int height) {
        if (Platform.isWindows()) {
            WebView2Host.show(url, x, y, width, height);
        } else if (Platform.isMac()) {
            WKWebViewHost.show(url, x, y, width, height);
        }
    }

    public static void hide() {
        if (Platform.isWindows()) {
            WebView2Host.hide();
        } else if (Platform.isMac()) {
            WKWebViewHost.hide();
        }
    }

    public static void focusGame() {
        if (Platform.isWindows()) {
            WebView2Host.focusGame();
        } else if (Platform.isMac()) {
            WKWebViewHost.focusGame();
        }
    }

    public static void close() {
        if (Platform.isWindows()) {
            WebView2Host.close();
        } else if (Platform.isMac()) {
            WKWebViewHost.close();
        }
    }

    /** Each tick while the editor screen is open. */
    public static void tick() {
        if (Platform.isMac()) {
            WKWebViewHost.tick();
        }
    }
}
