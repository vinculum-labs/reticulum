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
        // Where each label goes, nearest first: a label that would sit on a nearer one (looking straight along an
        // axis puts both ends in the middle) moves aside so both stay readable.
        placed.sort(Comparator.comparingDouble(Placed::depth));
        java.util.Map<Placed, int[]> spots = new java.util.HashMap<>();
        List<int[]> taken = new ArrayList<>();
        for (Placed p : placed) {
            int baseX = Math.round(centerX + p.sx() * radius);
            int y = Math.round(centerY + p.sy() * radius);
            int x = baseX;
            for (int offset : new int[] {0, 10, -10, 20, -20}) {
                x = baseX + offset;
                if (!overlaps(taken, x, y)) {
                    break;
                }
            }
            spots.put(p, new int[] {x, y});
            taken.add(new int[] {x, y});
        }
        // Then draw farthest first, so the directions towards the viewer draw on top of those pointing away.
        placed.sort(Comparator.comparingDouble((Placed p) -> -p.depth()));
        for (Placed p : placed) {
            boolean away = p.depth() > 0.05f;
            int color = ((away ? 0x90 : 0xFF) << 24) | p.axis().color();
            int ex = Math.round(centerX + p.sx() * radius);
            int ey = Math.round(centerY + p.sy() * radius);
            // A solid line from the middle, thicker when it comes towards the viewer, then the label on a small chip
            // so lines passing under it don't muddle the letter.
            // Lines are a little see-through (labels behind them still show); labels stay solid.
            line(graphics, centerX, centerY, ex, ey, away ? 1 : 2, ((away ? 0x70 : 0xC0) << 24) | p.axis().color());
            int[] at = spots.get(p);
            String label = p.axis().label();
            int w = font.width(label);
            graphics.fill(at[0] - w / 2 - 2, at[1] - 5, at[0] - w / 2 + w + 1, at[1] + 5, 0xB0000000);
            graphics.drawString(font, label, at[0] - w / 2, at[1] - 4, color, false);
        }
        graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, 0xFFFFFFFF);
    }

    /** Whether a label chip at x, y would overlap one already placed. */
    private static boolean overlaps(List<int[]> taken, int x, int y) {
        for (int[] t : taken) {
            if (Math.abs(t[0] - x) < 9 && Math.abs(t[1] - y) < 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * A straight line, 1 or 2 pixels thick, touching each pixel once so see-through colors stay even (the second
     * pixel sits beside the first, across the line's main direction).
     */
    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int thickness, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        boolean steep = Math.abs(y1 - y0) > Math.abs(x1 - x0);
        for (int i = 0; i <= steps; i++) {
            int x = steps == 0 ? x0 : x0 + Math.round((x1 - x0) * i / (float) steps);
            int y = steps == 0 ? y0 : y0 + Math.round((y1 - y0) * i / (float) steps);
            graphics.fill(x, y, x + 1, y + 1, color);
            if (thickness > 1) {
                graphics.fill(steep ? x + 1 : x, steep ? y : y + 1, steep ? x + 2 : x + 1, steep ? y + 1 : y + 2, color);
            }
        }
    }
}
