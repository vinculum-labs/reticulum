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
        // Nearly straight behind the view, a direction would pile up under the one pointing at the viewer: leave it out.
        placed.removeIf(p -> p.depth() > 0.2f && Mth.sqrt(p.sx() * p.sx() + p.sy() * p.sy()) < 0.5f);
        List<int[]> labels = new ArrayList<>();
        for (Placed p : placed) {
            labels.add(new int[] {Math.round(centerX + p.sx() * radius), Math.round(centerY + p.sy() * radius)});
        }
        boolean labelInMiddle = false;
        for (int n = 0; n < placed.size(); n++) {
            Placed p = placed.get(n);
            float length = Mth.sqrt(p.sx() * p.sx() + p.sy() * p.sy());
            boolean away = p.depth() > 0.2f;
            int color = ((away ? 0x70 : 0xFF) << 24) | p.axis().color();
            // The spoke: dots every 3 pixels from the middle, stopping short of its label and skipping any other label.
            float reach = length * radius - 7;
            for (float d = 3; d <= reach; d += 3) {
                int dx = Math.round(centerX + p.sx() / length * d);
                int dy = Math.round(centerY + p.sy() / length * d);
                if (labels.stream().noneMatch(l -> Math.abs(l[0] - dx) <= 5 && Math.abs(l[1] - dy) <= 6)) {
                    graphics.fill(dx, dy, dx + 1, dy + 1, color);
                }
            }
            int[] at = labels.get(n);
            labelInMiddle |= Math.abs(at[0] - centerX) <= 5 && Math.abs(at[1] - centerY) <= 6;
            graphics.drawString(font, p.axis().label(), at[0] - font.width(p.axis().label()) / 2, at[1] - 4, color, !away);
        }
        if (!labelInMiddle) {
            graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, 0xFFFFFFFF);
        }
    }
}
