package io.github.leadassimilator.reticulum.client;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/** Text over a block in the world: wrapped to a width, facing the camera, growing with distance so it stays readable. */
public final class WorldLabel {
    /** Lines wrap at this width (in the font's pixels). */
    public static final int WRAP = 180;
    /** The dark box behind the text. */
    private static final int BACKGROUND = 0x60000000;

    private WorldLabel() {
    }

    /**
     * Queues a label whose bottom line sits at {@code at}; the lines above it grow upwards. With {@code throughWalls},
     * end the batch with {@link #endThroughWalls} so blocks don't cover it.
     */
    public static void draw(PoseStack pose, Camera camera, MultiBufferSource buffers, Font font, Vec3 at, String text, int color, boolean throughWalls) {
        Vec3 eye = camera.position();
        double distance = Math.sqrt(eye.distanceToSqr(at));
        float scale = 0.025f * (float) Math.max(1, distance / 10);
        List<FormattedCharSequence> lines = font.split(Component.literal(text), WRAP);
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(camera.rotation());
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();
        // One font pixel away from the camera (it grows with the label, so it stays apart at any distance).
        Matrix4f behind = new Matrix4f(matrix).translate(0, 0, -1f);
        int lineHeight = font.lineHeight + 1;
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float y = (i - (lines.size() - 1)) * lineHeight;
            float x = -font.width(line) / 2f;
            if (throughWalls) {
                font.drawInBatch(line, x, y, 0xFF000000 | color, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, BACKGROUND, LightCoordsUtil.FULL_BRIGHT);
            } else {
                // Minecraft puts the background a hair in front of the letters (as underlines go over text), too close
                // for the depth buffer to tell apart: the background hid letters, in bands that moved as you did. So the
                // background goes on its own (its letters all but invisible), set clearly behind, then the letters.
                font.drawInBatch(line, x, y, 0x04000000, false, behind, buffers, Font.DisplayMode.NORMAL, BACKGROUND, LightCoordsUtil.FULL_BRIGHT);
                font.drawInBatch(line, x, y, 0xFF000000 | color, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
            }
        }
        pose.popPose();
    }

    /** Draws what is queued now, so blocks drawn later don't cover it (see-through text tests no depth). */
    public static void endThroughWalls(MultiBufferSource.BufferSource buffers) {
        buffers.endBatch();
    }
}
