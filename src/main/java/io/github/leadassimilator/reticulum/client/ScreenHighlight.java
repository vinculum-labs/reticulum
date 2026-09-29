package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.reticulum.client.screen.BlockScreen;
import io.github.leadassimilator.reticulum.client.screen.DeviceFacesScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || block == null
                || !(mc.screen instanceof BlockScreen || mc.screen instanceof DeviceFacesScreen)) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // A gentle pulse so it stands out against blocks of any color.
        float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 180.0);
        if (face == null) {
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(block).inflate(0.004), 1f, 0.8f, 0.15f, pulse);
        } else {
            fillFace(pose.last().pose(), buffers.getBuffer(RenderType.debugQuads()), block, face, 1f, 0.8f, 0.15f, 0.35f * pulse);
            buffers.endBatch(RenderType.debugQuads());
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), faceBox(block, face), 1f, 0.8f, 0.15f, pulse);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** A flat box lying on the face, just outside the block. */
    private static AABB faceBox(BlockPos pos, Direction side) {
        AABB box = new AABB(pos);
        double e = 0.006;
        return switch (side) {
            case DOWN -> new AABB(box.minX, box.minY - e, box.minZ, box.maxX, box.minY - e, box.maxZ);
            case UP -> new AABB(box.minX, box.maxY + e, box.minZ, box.maxX, box.maxY + e, box.maxZ);
            case NORTH -> new AABB(box.minX, box.minY, box.minZ - e, box.maxX, box.maxY, box.minZ - e);
            case SOUTH -> new AABB(box.minX, box.minY, box.maxZ + e, box.maxX, box.maxY, box.maxZ + e);
            case WEST -> new AABB(box.minX - e, box.minY, box.minZ, box.minX - e, box.maxY, box.maxZ);
            case EAST -> new AABB(box.maxX + e, box.minY, box.minZ, box.maxX + e, box.maxY, box.maxZ);
        };
    }

    /** A translucent quad over the face, drawn both ways round so it shows from either side. */
    private static void fillFace(Matrix4f matrix, VertexConsumer buffer, BlockPos pos, Direction side, float r, float g, float b, float a) {
        AABB f = faceBox(pos, side);
        float[][] corners;
        if (side.getAxis() == Direction.Axis.Y) {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.maxX, (float) f.minY, (float) f.minZ},
                    {(float) f.maxX, (float) f.minY, (float) f.maxZ}, {(float) f.minX, (float) f.minY, (float) f.maxZ}};
        } else if (side.getAxis() == Direction.Axis.Z) {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.maxX, (float) f.minY, (float) f.minZ},
                    {(float) f.maxX, (float) f.maxY, (float) f.minZ}, {(float) f.minX, (float) f.maxY, (float) f.minZ}};
        } else {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.minX, (float) f.minY, (float) f.maxZ},
                    {(float) f.minX, (float) f.maxY, (float) f.maxZ}, {(float) f.minX, (float) f.maxY, (float) f.minZ}};
        }
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2]).setColor(r, g, b, a);
        }
        for (int i = 3; i >= 0; i--) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2]).setColor(r, g, b, a);
        }
    }
}
