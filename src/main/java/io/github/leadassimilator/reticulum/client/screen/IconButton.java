package io.github.leadassimilator.reticulum.client.screen;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A normal button with a 16x16 icon (a sprite from textures/gui/sprites) in place of its label. The label still names
 * it for narration, and the tooltip says what it does.
 */
public class IconButton extends Button {
    private static final int SIZE = 16;

    private final ResourceLocation icon;

    public IconButton(int x, int y, int width, int height, String icon, Component label, Component tooltip, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.icon = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, icon);
        setTooltip(Tooltip.create(tooltip));
    }

    /** The icon instead of the label, dimmed when the button can't be used. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        float shade = active ? 1f : 0.5f;
        graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, icon, getX() + (getWidth() - SIZE) / 2,
                getY() + (getHeight() - SIZE) / 2, SIZE, SIZE, net.minecraft.util.ARGB.colorFromFloat(alpha, shade, shade, shade));
    }
}
