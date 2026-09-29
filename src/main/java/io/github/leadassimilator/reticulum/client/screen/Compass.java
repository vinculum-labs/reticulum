package io.github.leadassimilator.reticulum.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * A small 3D compass for the quick screens: the six directions as seen from where the player is looking, so
 * "West" or "Up" in a screen can be matched to the world. North is red; directions pointing away are dimmer.
 */
final class Compass {
    private record Axis(String label, float x, float y, float z, int color) {
    }

    private static final Axis[] AXES = {
            new Axis("N", 0, 0, -1, 0xFF5050),
            new Axis("S", 0, 0, 1, 0xE0E0E0),
            new Axis("E", 1, 0, 0, 0xE0E0E0),
            new Axis("W", -1, 0, 0, 0xE0E0E0),
            new Axis("U", 0, 1, 0, 0x80C0FF),
            new Axis("D", 0, -1, 0, 0x80C0FF),
    };

    private Compass() {
    }

    static void render(GuiGraphics graphics, Font font, int centerX, int centerY, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        float yaw = mc.player.getViewYRot(1f) * Mth.DEG_TO_RAD;
        float pitch = mc.player.getViewXRot(1f) * Mth.DEG_TO_RAD;
        // The camera's right, up and forward in world terms (Minecraft: yaw 0 faces south, positive pitch looks down).
        float rx = -Mth.cos(yaw);
        float rz = -Mth.sin(yaw);
        float fx = -Mth.sin(yaw) * Mth.cos(pitch);
        float fy = -Mth.sin(pitch);
        float fz = Mth.cos(yaw) * Mth.cos(pitch);
        // up = right x forward
        float ux = 0 * fz - rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy - 0 * fx;

        graphics.fill(centerX - radius - 8, centerY - radius - 8, centerX + radius + 8, centerY + radius + 8, 0x50000000);
        record Placed(Axis axis, float sx, float sy, float depth) {
        }
        List<Placed> placed = new ArrayList<>();
        for (Axis a : AXES) {
            float sx = a.x * rx + a.z * rz;
            float sy = -(a.x * ux + a.y * uy + a.z * uz);
            float depth = a.x * fx + a.y * fy + a.z * fz;
            placed.add(new Placed(a, sx, sy, depth));
        }
        // Farthest first, so the directions towards the viewer draw on top.
        placed.sort(Comparator.comparingDouble((Placed p) -> -p.depth()));
        for (Placed p : placed) {
            boolean away = p.depth() > 0.2f;
            int alpha = away ? 0x70 : 0xFF;
            int color = (alpha << 24) | p.axis().color();
            // The spoke: a row of dots from the middle out to the label.
            for (int i = 1; i <= 6; i++) {
                int dx = Math.round(centerX + p.sx() * radius * i / 7f);
                int dy = Math.round(centerY + p.sy() * radius * i / 7f);
                graphics.fill(dx, dy, dx + 1, dy + 1, color);
            }
            int lx = Math.round(centerX + p.sx() * radius);
            int ly = Math.round(centerY + p.sy() * radius);
            graphics.drawString(font, p.axis().label(), lx - font.width(p.axis().label()) / 2, ly - 4, color, !away);
        }
        graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, 0xFFFFFFFF);
    }
}
