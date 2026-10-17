package io.github.leadassimilator.reticulum.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text box with a faint background instead of vanilla's solid black one, so the world (and what the screen
 * highlights in it) shows through. It takes the same space and clicks as a normal box of that size.
 */
class SeeThroughTextBox extends EditBox {
    private static final int PAD_X = 4;

    private final int frameX;
    private final int frameY;
    private int frameWidth;
    private final int frameHeight;

    SeeThroughTextBox(Font font, int x, int y, int width, int height, Component message) {
        // Without a border, vanilla draws the text at the widget's corner, so the widget is the text area inside the frame.
        super(font, x + PAD_X, y + (height - 8) / 2, width - 2 * PAD_X, 10, message);
        setBordered(false);
        frameX = x;
        frameY = y;
        frameWidth = width;
        frameHeight = height;
    }

    /** Narrowed or widened, the frame (what is drawn and clicked) goes with the text area. */
    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        frameWidth = width + 2 * PAD_X;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        graphics.fill(frameX, frameY, frameX + frameWidth, frameY + frameHeight, 0x50000000);
        int border = isFocused() ? 0xFFFFFFFF : isHovered() ? 0xC0C0C0C0 : 0x80A0A0A0;
        graphics.submitOutline(frameX, frameY, frameWidth, frameHeight, border);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    private boolean inFrame(double mouseX, double mouseY) {
        return mouseX >= frameX && mouseX < frameX + frameWidth && mouseY >= frameY && mouseY < frameY + frameHeight;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isVisible() && isActive() && inFrame(mouseX, mouseY);
    }
}
