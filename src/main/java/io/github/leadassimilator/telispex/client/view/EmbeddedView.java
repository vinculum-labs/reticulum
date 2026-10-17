package io.github.leadassimilator.telispex.client.view;

import javax.annotation.Nullable;

import com.sun.jna.Platform;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The web view inside the game window, whichever the system provides: WebView2 on Windows, WKWebView on macOS,
 * WebKitGTK on Linux. Elsewhere there is none, and pages open in the browser. There is one view, holding one app's page
 * at a time.
 */
public final class EmbeddedView {
    /** The color behind the page until it paints (and the view screen's placeholder), as 0xRRGGBB. */
    public static final int BACKGROUND = 0x12151B;

    /** What the view screen needs from a system's web view. */
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

        // Input, for a view the game draws (positions are view pixels from its top left).

        /** Whether the game draws the view now, so the screen passes it the mouse. */
        default boolean drawnByGame() {
            return false;
        }

        default void mouseMove(int x, int y) {
        }

        default void mouseLeave() {
        }

        /** A mouse button (GLFW numbering) going down or up. */
        default void button(int button, boolean down, int x, int y) {
        }

        /** A wheel turned, as GLFW reports it (positive y is up, positive x is left). */
        default void wheel(double scrollX, double scrollY, int x, int y) {
        }

        /** Gives the keyboard to the view. */
        default void focusView() {
        }

        /** Whether the game should pass the view its keys (when the view can't take them from the system itself). */
        default boolean keysToView() {
            return false;
        }

        /** A key (GLFW key, scancode and modifiers) going down or up. */
        default void key(int key, int scancode, int modifiers, boolean down) {
        }

        /** Whether a mouse button (GLFW numbering) is really down. */
        default boolean buttonDown(int button) {
            return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
        }

        /** The GLFW cursor shape the page asks for (0 for the default). */
        default int cursor() {
            return 0;
        }

        /** Each tick while the view screen is open. */
        default void tick() {
        }

        /** The game is closing: stop what the view runs on, while the process is still whole. */
        default void shutdown() {
        }
    }

    /** Chosen once; a host's class is only loaded on its own system. */
    private static final Host HOST = Platform.isWindows() ? windows() : Platform.isMac() ? mac() : Platform.isLinux() ? linux() : none();

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
            public boolean drawnByGame() {
                return WebView2Host.drawnInFrame();
            }

            @Override
            public void mouseMove(int x, int y) {
                WebView2Host.mouseMove(x, y);
            }

            @Override
            public void mouseLeave() {
                WebView2Host.mouseLeave();
            }

            @Override
            public void button(int button, boolean down, int x, int y) {
                WebView2Host.button(button, down, x, y);
            }

            @Override
            public void wheel(double scrollX, double scrollY, int x, int y) {
                WebView2Host.wheel(scrollX, scrollY, x, y);
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

    private static Host linux() {
        return new Host() {
            @Override
            public boolean supported() {
                return WebKitGtkHost.supported();
            }

            @Override
            public boolean ready() {
                return WebKitGtkHost.ready();
            }

            @Override
            public boolean shown() {
                return WebKitGtkHost.shown();
            }

            @Override
            public void show(@Nullable String url, int x, int y, int width, int height) {
                WebKitGtkHost.show(url, x, y, width, height);
            }

            @Override
            public void hide() {
                WebKitGtkHost.hide();
            }

            @Override
            public void focusGame() {
                WebKitGtkHost.focus(false);
            }

            @Override
            public void close() {
                WebKitGtkHost.close();
            }

            @Override
            public void shutdown() {
                WebKitGtkHost.shutdown();
            }

            @Override
            @Nullable
            public ResourceLocation frame() {
                return WebKitGtkHost.frame();
            }

            @Override
            public int frameWidth() {
                return WebKitGtkHost.frameWidth();
            }

            @Override
            public int frameHeight() {
                return WebKitGtkHost.frameHeight();
            }

            @Override
            public boolean drawnByGame() {
                return WebKitGtkHost.drawnByGame();
            }

            @Override
            public void mouseMove(int x, int y) {
                WebKitGtkHost.mouseMove(x, y);
            }

            @Override
            public void mouseLeave() {
                WebKitGtkHost.mouseLeave();
            }

            @Override
            public void button(int button, boolean down, int x, int y) {
                WebKitGtkHost.button(button, down, x, y);
            }

            @Override
            public void wheel(double scrollX, double scrollY, int x, int y) {
                WebKitGtkHost.wheel(scrollX, scrollY, x, y);
            }

            @Override
            public void focusView() {
                WebKitGtkHost.focus(true);
            }

            @Override
            public boolean keysToView() {
                return WebKitGtkHost.hasKeyboard();
            }

            @Override
            public void key(int key, int scancode, int modifiers, boolean down) {
                WebKitGtkHost.key(scancode, modifiers, down);
            }

            @Override
            public int cursor() {
                return WebKitGtkHost.cursor();
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

    /**
     * Whether the view reaches {@code <name>.localhost} on this computer by itself. WebView2 does (Chromium sends every
     * such name to the loopback address); WKWebView and WebKitGTK ask the system, which may not know them.
     */
    public static boolean takesLocalhostNames() {
        return Platform.isWindows();
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

    /** Closes the page (leaving the world): the view holds none until the next opens. */
    public static void close() {
        HOST.close();
        ViewScreen.forget();
    }

    /** Each tick while the view screen is open. */
    public static void tick() {
        HOST.tick();
    }

    /** The game is closing. */
    public static void shutdown() {
        HOST.shutdown();
    }

    /** The view's latest frame for the screen to draw, when the game draws it; null when the system shows the view. */
    @Nullable
    public static ResourceLocation frame() {
        return HOST.frame();
    }

    /** Whether the game draws the view now, so the screen passes it the mouse. */
    public static boolean drawnByGame() {
        return HOST.drawnByGame();
    }

    public static void mouseMove(int x, int y) {
        HOST.mouseMove(x, y);
    }

    public static void mouseLeave() {
        HOST.mouseLeave();
    }

    /** A mouse button (GLFW numbering) for a view the game draws, at view pixels. */
    public static void button(int button, boolean down, int x, int y) {
        HOST.button(button, down, x, y);
    }

    /** A wheel turned, as GLFW reports it, for a view the game draws. */
    public static void wheel(double scrollX, double scrollY, int x, int y) {
        HOST.wheel(scrollX, scrollY, x, y);
    }

    public static void focusView() {
        HOST.focusView();
    }

    /** Whether the screen should pass the view its keys. */
    public static boolean keysToView() {
        return HOST.keysToView();
    }

    public static void key(int key, int scancode, int modifiers, boolean down) {
        HOST.key(key, scancode, modifiers, down);
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
