package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.leadassimilator.reticulum.client.screen.BlockScreen;
import io.github.leadassimilator.reticulum.client.screen.DeviceFacesScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While a connector or cable screen is open, shows in the world what the focused text box names: the connector,
 * a device on one side, or just one face of that device.
 */
public final class ScreenHighlight {
    /** How far outside the block a face is drawn. */
    private static final double MARGIN = 0.006;
    @Nullable
    private static BlockPos block;
    /** The face to show, or null for the whole block. */
    @Nullable
    private static Direction face;

    private ScreenHighlight() {
    }

    /** What to show while the screen stays open; a null block shows nothing. */
    public static void set(@Nullable BlockPos target, @Nullable Direction targetFace) {
        block = target;
        face = targetFace;
    }

    public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (block == null
                || !(mc.screen instanceof BlockScreen || mc.screen instanceof DeviceFacesScreen)) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // A gentle pulse so it stands out against blocks of any color.
        float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 180.0);
        if (face == null) {
            ShapeRenderer.renderLineBox(pose.last(), buffers.getBuffer(RenderType.lines()), new AABB(block).inflate(0.004), 1f, 0.8f, 0.15f, pulse);
        } else {
            WorldOverlay.fillFace(pose.last().pose(), buffers.getBuffer(RenderType.debugQuads()), block, face, MARGIN, 1f, 0.8f, 0.15f, 0.35f * pulse);
            buffers.endBatch(RenderType.debugQuads());
            ShapeRenderer.renderLineBox(pose.last(), buffers.getBuffer(RenderType.lines()), WorldOverlay.faceBox(block, face, MARGIN), 1f, 0.8f, 0.15f, pulse);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
