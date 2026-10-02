package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import com.sun.jna.Platform;

import net.minecraft.resources.ResourceLocation;

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

        /** The view's latest frame, when the game draws the view itself (rather than the system showing it). */
        @Nullable
        default ResourceLocation frame() {
            return null;
        }

        default int frameWidth() {
            return 0;
        }

        default int frameHeight() {
            return 0;
        }

        /** Mouse input for a view the game draws: a move, wheel or leave (Windows message numbers), at view pixels. */
        default void mouse(int kind, int keys, int data, int x, int y) {
        }

        /** A mouse button (GLFW numbering) down or up, at view pixels, for a view the game draws. */
        default void button(int button, boolean down, int keys, int x, int y) {
        }

        /** Gives the keyboard to a view the game draws. */
        default void focusView() {
        }

        /** Whether a mouse button (GLFW numbering) is really down, for a view the game draws. */
        default boolean buttonDown(int button) {
            return true;
        }

        /** The GLFW cursor shape the page asks for, for a view the game draws (0 for the default). */
        default int cursor() {
            return 0;
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
                return !WebView2Host.drawsInFrame();
            }

            @Override
            @Nullable
            public ResourceLocation frame() {
                return WebView2Host.frame();
            }

            @Override
            public int frameWidth() {
                return CompositionView.frameWidth();
            }

            @Override
            public int frameHeight() {
                return CompositionView.frameHeight();
            }

            @Override
            public void mouse(int kind, int keys, int data, int x, int y) {
                WebView2Host.mouse(kind, keys, data, x, y);
            }

            @Override
            public void button(int button, boolean down, int keys, int x, int y) {
                WebView2Host.button(button, down, keys, x, y);
            }

            @Override
            public void focusView() {
                WebView2Host.focusView();
            }

            @Override
            public boolean buttonDown(int button) {
                return WebView2Host.buttonDown(button);
            }

            @Override
            public int cursor() {
                return WebView2Host.cursor();
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

    /** The view's latest frame for the screen to draw, when the game draws it; null when the system shows the view. */
    @Nullable
    public static ResourceLocation frame() {
        return HOST.frame();
    }

    /** Whether the game draws the view now, so the screen passes it the mouse. */
    public static boolean drawnByGame() {
        return Platform.isWindows() && WebView2Host.drawnInFrame();
    }

    /** Mouse moves, wheels and leaves (Windows message numbers) for a view the game draws, at view pixels. */
    public static void mouse(int kind, int keys, int data, int x, int y) {
        HOST.mouse(kind, keys, data, x, y);
    }

    /** A mouse button (GLFW numbering) for a view the game draws, at view pixels. */
    public static void button(int button, boolean down, int keys, int x, int y) {
        HOST.button(button, down, keys, x, y);
    }

    public static void focusView() {
        HOST.focusView();
    }

    /** Whether a mouse button (GLFW numbering) is really down (the game may not hear it come up). */
    public static boolean buttonDown(int button) {
        return HOST.buttonDown(button);
    }

    /** The GLFW cursor shape the page asks for (0 for the default arrow). */
    public static int cursor() {
        return HOST.cursor();
    }

    /** The frame's size in pixels. */
    public static int frameWidth() {
        return HOST.frameWidth();
    }

    public static int frameHeight() {
        return HOST.frameHeight();
    }
}
