package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;

/**
 * Fullscreen while the in-game editor is open. Exclusive fullscreen draws past Windows' compositor, so no child
 * window (the editor) can show over it; while the editor is up, the game is instead a borderless window covering its
 * monitor, which looks the same. Closing the editor goes back to exclusive fullscreen.
 *
 * <p>The game's fullscreen option stays on throughout, so quitting meanwhile still starts fullscreen next time.
 */
final class Borderless {
    /** The window's place and size before it went borderless (x, y, width, height), to put back. */
    @Nullable
    private static int[] windowed;

    private Borderless() {
    }

    static boolean active() {
        return windowed != null;
    }

    /** Exclusive fullscreen to borderless (opening the editor while fullscreen). */
    static void enterFromFullscreen() {
        Window window = Minecraft.getInstance().getWindow();
        // Back to a normal window first, where the game remembers its windowed place and size.
        window.toggleFullScreen();
        enter(window);
    }

    /** A normal window to borderless (F11 inside the editor). */
    static void enterFromWindowed() {
        enter(Minecraft.getInstance().getWindow());
        Minecraft.getInstance().options.fullscreen().set(true);
    }

    private static void enter(Window window) {
        long handle = window.getWindow();
        if (windowed == null) {
            int[] x = new int[1];
            int[] y = new int[1];
            int[] w = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            windowed = new int[] {x[0], y[0], w[0], h[0]};
        }
        Monitor monitor = window.findBestMonitor();
        GLFWVidMode mode = monitor == null ? null : GLFW.glfwGetVideoMode(monitor.getMonitor());
        if (monitor == null || mode == null) {
            // No monitor to cover: stay a normal window (the editor still shows).
            windowed = null;
            return;
        }
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(handle, 0, monitor.getX(), monitor.getY(), mode.width(), mode.height(), GLFW.GLFW_DONT_CARE);
        Minecraft.getInstance().resizeDisplay();
    }

    /**
     * Borderless back to a normal window, then (when {@code toFullscreen}) to exclusive fullscreen as before; without
     * it (F11 inside the editor), the game stays windowed and its fullscreen option goes off.
     */
    static void leave(boolean toFullscreen) {
        if (windowed == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        long handle = window.getWindow();
        int[] rect = windowed;
        windowed = null;
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowMonitor(handle, 0, rect[0], rect[1], rect[2], rect[3], GLFW.GLFW_DONT_CARE);
        if (toFullscreen) {
            // The game notes the (restored) windowed place and size as it goes fullscreen, as it always does.
            window.toggleFullScreen();
        } else {
            mc.options.fullscreen().set(false);
        }
        mc.resizeDisplay();
    }
}
