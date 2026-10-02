package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import io.github.leadassimilator.reticulum.client.web.WebBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The editor inside the game: a thin bar across the top (title, Open in browser, Close) with the web view filling
 * the rest of the window. The game keeps running underneath, as it does while the editor is open in a browser.
 */
public class EditorScreen extends Screen {
    private static final int BAR = 20;

    /** The page to load the first time this screen lays itself out; null keeps the page already loaded. */
    @Nullable
    private String url;

    public static void open(@Nullable String url) {
        Minecraft.getInstance().setScreen(new EditorScreen(url));
    }

    /** The web view couldn't start: close this and use the browser, as before. */
    static void fallBackToBrowser() {
        if (Minecraft.getInstance().screen instanceof EditorScreen) {
            Minecraft.getInstance().setScreen(null);
        }
        WebBridge.openInBrowser();
    }

    private EditorScreen(@Nullable String url) {
        super(Component.translatable("screen.reticulum.editor"));
        this.url = url;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.editor_close"), b -> onClose())
                .bounds(width - 62, 2, 60, BAR - 4).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.editor_browser"), b -> {
            onClose();
            WebBridge.openInBrowser();
        }).bounds(width - 62 - 104, 2, 100, BAR - 4).build());
        var window = Minecraft.getInstance().getWindow();
        if (window.isFullscreen() && EmbeddedView.hiddenByFullscreen()) {
            // Exclusive fullscreen draws past Windows' compositor, so a child window never shows, and taking focus
            // for it minimizes the game. Wait for windowed mode (F11 here reaches the game) or the browser.
            EmbeddedView.hide();
            return;
        }
        // The view covers the window below the bar, in window pixels.
        int barPixels = (int) Math.ceil(BAR * window.getGuiScale());
        EmbeddedView.show(url, 0, barPixels, window.getWidth(), window.getHeight() - barPixels);
        url = null;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Only the bar shows; the web view covers everything below it. The placeholder stays under the view even once
        // it's shown: on macOS the game's next frame can reach the screen before the view is composited, and the world
        // would flash through.
        graphics.fill(0, 0, width, BAR, 0xFF1A1E26);
        graphics.fill(0, BAR - 1, width, BAR, 0xFF2F3542);
        graphics.fill(0, BAR, width, height, 0xFF000000 | EmbeddedView.BACKGROUND);
        ResourceLocation frame = EmbeddedView.frame();
        if (frame != null) {
            // Drawn by the game: the view's frame, one texel per window pixel below the bar (in GUI units it would be
            // stretched by the rounding of the GUI scale, and the text would blur).
            int w = EmbeddedView.frameWidth();
            int h = EmbeddedView.frameHeight();
            float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
            graphics.pose().pushPose();
            graphics.pose().scale(1 / scale, 1 / scale, 1);
            graphics.blit(frame, 0, (int) Math.ceil(BAR * scale), w, h, 0, 0, w, h, w, h);
            graphics.pose().popPose();
            return;
        }
        if (Minecraft.getInstance().getWindow().isFullscreen() && EmbeddedView.hiddenByFullscreen()) {
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.editor_fullscreen"), width / 2, height / 2 - 6, 0xE3E7EF);
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.editor_fullscreen_hint"), width / 2, height / 2 + 6, 0xA0A0A0);
        } else if (!EmbeddedView.shown()) {
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.editor_loading"), width / 2, height / 2, 0xA0A0A0);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        followMouse();
        if (inView) {
            setCursor(EmbeddedView.cursor());
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 6, (BAR - font.lineHeight) / 2 + 1, 0xE3E7EF);
    }

    /**
     * A click on the bar takes the keyboard back from the view, so Esc there closes the editor. A click in a view the
     * game draws goes to the page, which takes the keyboard.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (EmbeddedView.drawnByGame() && mouseY >= BAR) {
            buttons |= 1 << button;
            EmbeddedView.button(button, true, keys(), viewX(mouseX), viewY(mouseY));
            EmbeddedView.focusView();
            return true;
        }
        EmbeddedView.focusGame();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- the mouse, for a view the game draws

    /** Mouse buttons down in the view (bit per GLFW button), so their release goes there wherever it happens. */
    private int buttons;
    /** The mouse is over the view (it was told it left otherwise). */
    private boolean inView;
    private int cursorShape;
    private long cursor;
    /** Where the mouse was last sent, in view pixels. */
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if ((buttons & 1 << button) != 0) {
            if (EmbeddedView.buttonDown(button)) {
                // Not a real release: GLFW reports every button up when the game window loses the keyboard, which the
                // page taking it on the first click does. The real one is caught in followMouse.
                return true;
            }
            buttons &= ~(1 << button);
            EmbeddedView.button(button, false, keys(), viewX(mouseX), viewY(mouseY));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Sends the mouse's moves to a view the game draws. Read from GLFW each frame rather than taken from the game's
     * move events: the game drops those while the page has the keyboard (its window counts as inactive then).
     */
    private void followMouse() {
        if (!EmbeddedView.drawnByGame()) {
            return;
        }
        var window = Minecraft.getInstance().getWindow();
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(window.getWindow(), x, y);
        int viewX = (int) x[0];
        int viewY = (int) y[0] - (int) Math.ceil(BAR * window.getGuiScale());
        // A release the game never heard (the page captured the mouse while dragging) still ends the drag.
        for (int button = 0; button < 5; button++) {
            if ((buttons & 1 << button) != 0 && !EmbeddedView.buttonDown(button)) {
                buttons &= ~(1 << button);
                EmbeddedView.button(button, false, keys(), viewX, viewY);
            }
        }
        if (viewX == lastX && viewY == lastY) {
            return;
        }
        lastX = viewX;
        lastY = viewY;
        if (viewY >= 0 || buttons != 0) {
            inView = true;
            EmbeddedView.mouse(WebView2Host.MOUSE_MOVE, keys(), 0, viewX, viewY);
        } else if (inView) {
            inView = false;
            EmbeddedView.mouse(WebView2Host.MOUSE_LEAVE, 0, 0, 0, 0);
            setCursor(0);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (EmbeddedView.drawnByGame() && mouseY >= BAR) {
            // 120 per notch, as Windows counts; GLFW reports horizontal wheels the other way round.
            if (scrollY != 0) {
                EmbeddedView.mouse(WebView2Host.MOUSE_WHEEL, keys(), (int) Math.round(scrollY * 120), viewX(mouseX), viewY(mouseY));
            }
            if (scrollX != 0) {
                EmbeddedView.mouse(WebView2Host.MOUSE_HORIZONTAL_WHEEL, keys(), (int) Math.round(-scrollX * 120), viewX(mouseX), viewY(mouseY));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The buttons held, as COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS (the host adds Shift and Ctrl). */
    private int keys() {
        int keys = (buttons & 1) != 0 ? 0x1 : 0;
        keys |= (buttons & 2) != 0 ? 0x2 : 0;
        keys |= (buttons & 4) != 0 ? 0x10 : 0;
        return keys;
    }

    /** Screen coordinates to the view's pixels (its top left is just below the bar). */
    private int viewX(double mouseX) {
        return (int) (mouseX * Minecraft.getInstance().getWindow().getGuiScale());
    }

    private int viewY(double mouseY) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return (int) (mouseY * scale) - (int) Math.ceil(BAR * scale);
    }

    /** Shows the cursor the page asks for (a hand over links, a bar over text). */
    private void setCursor(int shape) {
        if (shape == cursorShape) {
            return;
        }
        cursorShape = shape;
        if (cursor != 0) {
            GLFW.glfwDestroyCursor(cursor);
            cursor = 0;
        }
        if (shape != 0) {
            cursor = GLFW.glfwCreateStandardCursor(shape);
        }
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), cursor);
    }

    @Override
    public void removed() {
        setCursor(0);
        EmbeddedView.hide();
    }

    @Override
    public void tick() {
        EmbeddedView.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
