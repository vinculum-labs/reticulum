package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The sounds matching a search, as a list over the screen under its box (or above it, where there is more room): as
 * many rows as fit, scrolled with the mouse wheel, a click picking one. Its screen adds it last (so it draws on top)
 * and hands it clicks and scrolls before anything else.
 */
final class SoundDropdown extends AbstractWidget {
    private static final int ROW = 12;
    private static final int MAX_ROWS = 8;

    private final Font font;
    private final List<String> items;
    private final Consumer<String> onPick;
    private final int rows;
    private int scroll;

    /** Under (or over) a box at {@code boxY} of {@code boxHeight}, within a screen {@code screenHeight} tall. */
    SoundDropdown(Font font, int x, int boxY, int boxHeight, int width, int screenHeight, List<String> items, Consumer<String> onPick) {
        super(x, 0, width, 0, Component.empty());
        this.font = font;
        this.items = items;
        this.onPick = onPick;
        int below = screenHeight - (boxY + boxHeight) - 4;
        int above = boxY - 4;
        int room = Math.max(below, above);
        this.rows = Math.max(1, Math.min(Math.min(MAX_ROWS, items.size()), (room - 4) / ROW));
        int height = rows * ROW + 4 + (items.size() > rows ? ROW : 0);
        setHeight(height);
        setY(below >= height || below >= above ? boxY + boxHeight + 1 : boxY - height - 1);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        int x = getX();
        int y = getY();
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFFA0A0A0);
        graphics.fill(x, y, x + width, y + height, 0xF0101014);
        for (int i = 0; i < rows && scroll + i < items.size(); i++) {
            int rowY = y + 2 + i * ROW;
            boolean hover = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW;
            if (hover) {
                graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW, 0xFF30343C);
            }
            graphics.drawString(font, font.plainSubstrByWidth(items.get(scroll + i), width - 10), x + 4, rowY + 2, hover ? 0xFFFFFF : 0xD0D0D0);
        }
        if (items.size() > rows) {
            // Where the list is, and how many there are.
            int trackTop = y + 2;
            int trackHeight = rows * ROW;
            int thumb = Math.max(6, trackHeight * rows / items.size());
            int thumbTop = trackTop + (trackHeight - thumb) * scroll / Math.max(1, items.size() - rows);
            graphics.fill(x + width - 4, thumbTop, x + width - 1, thumbTop + thumb, 0xFF808080);
            String more = Component.translatable("screen.reticulum.sound_more", scroll + 1, Math.min(items.size(), scroll + rows), items.size()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(more, width - 8), x + 4, y + 2 + rows * ROW + 2, 0x808080);
        }
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int row = (int) ((mouseY - getY() - 2) / ROW);
        if (row >= 0 && row < rows && scroll + row < items.size()) {
            onPick.accept(items.get(scroll + row));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(items.size() - rows, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
