package io.github.leadassimilator.reticulum.client.screen;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A normal button with a 16x16 icon (a sprite from textures/gui/sprites) in place of its label. The label still names
 * it for narration, and the tooltip says what it does.
 */
public class IconButton extends Button {
    private static final int SIZE = 16;

    private final Identifier icon;

    public IconButton(int x, int y, int width, int height, String icon, Component label, Component tooltip, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.icon = Identifier.fromNamespaceAndPath(Reticulum.MODID, icon);
        setTooltip(Tooltip.create(tooltip));
    }

    /** The button, with the icon instead of the label, dimmed when the button can't be used. */
    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderDefaultSprite(graphics);
        float shade = active ? 1f : 0.5f;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, getX() + (getWidth() - SIZE) / 2,
                getY() + (getHeight() - SIZE) / 2, SIZE, SIZE, ARGB.colorFromFloat(alpha, shade, shade, shade));
    }
}
