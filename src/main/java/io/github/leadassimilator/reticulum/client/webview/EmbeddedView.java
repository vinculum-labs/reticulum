package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import com.sun.jna.Platform;

/**
 * The editor's web view inside the game window, whichever the system provides: WebView2 on Windows, WKWebView on
 * macOS. Elsewhere (Linux) there is none, and the editor opens in the browser.
 */
public final class EmbeddedView {
    /** The color behind the page until it paints (and the editor screen's placeholder), as 0xRRGGBB. */
    public static final int BACKGROUND = 0x12151B;

    /** What the editor screen needs from a system's web view. */
    private interface Host {
        boolean supported();

        /** The view exists (its page may still be loading). */
        boolean ready();

        /** The page has loaded, so the view is on screen when asked to be. */
        boolean shown();

        void show(@Nullable String url, int x, int y, int width, int height);

        void hide();

        void focusGame();

        void close();

        /** Whether the game's fullscreen hides child views (Windows' exclusive fullscreen bypasses the compositor). */
        default boolean hiddenByFullscreen() {
            return false;
        }

        /** Each tick while the editor screen is open. */
        default void tick() {
        }
    }

    /** Chosen once; a host's class is only loaded on its own system. */
    private static final Host HOST = Platform.isWindows() ? windows() : Platform.isMac() ? mac() : none();

    private EmbeddedView() {
    }

    private static Host windows() {
        return new Host() {
            @Override
            public boolean supported() {
                return WebView2Host.supported();
            }

            @Override
            public boolean ready() {
                return WebView2Host.ready();
            }

            @Override
            public boolean shown() {
                return WebView2Host.shown();
            }

            @Override
            public void show(@Nullable String url, int x, int y, int width, int height) {
                WebView2Host.show(url, x, y, width, height);
            }

            @Override
            public void hide() {
                WebView2Host.hide();
            }

            @Override
            public void focusGame() {
                WebView2Host.focusGame();
            }

            @Override
            public void close() {
                WebView2Host.close();
            }

            @Override
            public boolean hiddenByFullscreen() {
                return true;
            }
        };
    }

    private static Host mac() {
        return new Host() {
            @Override
            public boolean supported() {
                return WKWebViewHost.supported();
            }

            @Override
            public boolean ready() {
                return WKWebViewHost.ready();
            }

            @Override
            public boolean shown() {
                return WKWebViewHost.shown();
            }

            @Override
            public void show(@Nullable String url, int x, int y, int width, int height) {
                WKWebViewHost.show(url, x, y, width, height);
            }

            @Override
            public void hide() {
                WKWebViewHost.hide();
            }

            @Override
            public void focusGame() {
                WKWebViewHost.focusGame();
            }

            @Override
            public void close() {
                WKWebViewHost.close();
            }

            @Override
            public void tick() {
                WKWebViewHost.tick();
            }
        };
    }

    private static Host none() {
        return new Host() {
            @Override
            public boolean supported() {
                return false;
            }

            @Override
            public boolean ready() {
                return false;
            }

            @Override
            public boolean shown() {
                return false;
            }

            @Override
            public void show(@Nullable String url, int x, int y, int width, int height) {
            }

            @Override
            public void hide() {
            }

            @Override
            public void focusGame() {
            }

            @Override
            public void close() {
            }
        };
    }

    public static boolean supported() {
        return HOST.supported();
    }

    /** The view exists (its page may still be loading). */
    public static boolean ready() {
        return HOST.ready();
    }

    /** The page has loaded, so the view is on screen when asked to be. */
    public static boolean shown() {
        return HOST.shown();
    }

    /** Whether the game's fullscreen hides child views; macOS fullscreen is still composited, so the view shows there. */
    public static boolean hiddenByFullscreen() {
        return HOST.hiddenByFullscreen();
    }

    /** Shows the view at these window pixels (from the top left), loading {@code url} unless it is null. */
    public static void show(@Nullable String url, int x, int y, int width, int height) {
        HOST.show(url, x, y, width, height);
    }

    public static void hide() {
        HOST.hide();
    }

    public static void focusGame() {
        HOST.focusGame();
    }

    public static void close() {
        HOST.close();
    }

    /** Each tick while the editor screen is open. */
    public static void tick() {
        HOST.tick();
    }
}
