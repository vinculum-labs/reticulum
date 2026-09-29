package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.client.web.WebBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The editor inside the game: a thin bar across the top (title, Open in browser, Close) with the WebView2 view filling
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

    /** WebView2 couldn't start: close this and use the browser, as before. */
    /** F11 inside the editor: fullscreen (borderless while the editor is up) or back to a normal window. */
    public static void toggleFullscreen() {
        if (Borderless.active()) {
            Borderless.leave(false);
        } else if (!Minecraft.getInstance().getWindow().isFullscreen()) {
            Borderless.enterFromWindowed();
        }
    }

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
        if (window.isFullscreen()) {
            // Exclusive fullscreen draws past Windows' compositor, so the view can't show over it: switch to a
            // borderless window covering the monitor instead (done next tick, not in the middle of laying out). If
            // we were borderless already, the game's own F11 (pressed on the bar) asked for a normal window.
            WebView2Host.hide();
            Minecraft.getInstance().execute(() -> {
                if (Borderless.active()) {
                    window.toggleFullScreen();
                    Borderless.leave(false);
                } else {
                    Borderless.enterFromFullscreen();
                }
            });
            return;
        }
        // The view covers the window below the bar, in window pixels.
        int barPixels = (int) Math.ceil(BAR * window.getGuiScale());
        WebView2Host.show(url, 0, barPixels, window.getWidth(), window.getHeight() - barPixels);
        url = null;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Only the bar shows; the web view covers everything below it.
        graphics.fill(0, 0, width, BAR, 0xFF1A1E26);
        graphics.fill(0, BAR - 1, width, BAR, 0xFF2F3542);
        if (!WebView2Host.shown() || Minecraft.getInstance().getWindow().isFullscreen()) {
            graphics.fill(0, BAR, width, height, 0xFF12151B);
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.editor_loading"), width / 2, height / 2, 0xA0A0A0);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 6, (BAR - font.lineHeight) / 2 + 1, 0xE3E7EF);
    }

    /** A click on the bar takes the keyboard back from the view, so Esc there closes the editor. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        WebView2Host.focusGame();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        WebView2Host.hide();
        // Back to exclusive fullscreen if the editor had swapped it for borderless.
        Borderless.leave(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
