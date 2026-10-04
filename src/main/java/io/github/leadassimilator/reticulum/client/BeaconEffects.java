package io.github.leadassimilator.reticulum.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Status Beacons' glow (a pulsing bubble, through walls if set) and their alarm sound. */
public final class BeaconEffects {
    /** Glows further away than this aren't drawn. */
    private static final double GLOW_DISTANCE = 160;

    private BeaconEffects() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null
                || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float pulse = 0.5f + 0.5f * Mth.sin((float) (System.currentTimeMillis() % 100_000) / 180f);
        boolean any = false;
        for (StatusBeaconBlockEntity beacon : StatusBeaconBlockEntity.CLIENT_LOADED) {
            AlarmSeverity shown = beacon.shown();
            if (shown == null || beacon.getLevel() != mc.level || beacon.isRemoved()) {
                continue;
            }
            int style = beacon.style(shown);
            BlockPos pos = beacon.getBlockPos();
            if ((style & StatusBeaconBlockEntity.GLOW) == 0 || camera.distanceToSqr(Vec3.atCenterOf(pos)) > GLOW_DISTANCE * GLOW_DISTANCE) {
                continue;
            }
            boolean through = (style & StatusBeaconBlockEntity.GLOW_THROUGH) != 0;
            int color = shown == AlarmSeverity.CRITICAL ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            RenderType quads = through ? IdentifyRenderer.QUADS_THROUGH_WALLS : RenderType.debugQuads();
            RenderType lines = through ? IdentifyRenderer.LINES_THROUGH_WALLS : RenderType.lines();
            // Two shells breathing out of step, and an outline, so it reads as a bubble around the block.
            double outer = 0.35 + 0.35 * pulse;
            double inner = 0.15 + 0.2 * (1 - pulse);
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(outer), r, g, b, 0.10f + 0.10f * (1 - pulse));
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(inner), r, g, b, 0.18f);
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines), new AABB(pos).inflate(outer), r, g, b, 0.6f * (1 - pulse) + 0.2f);
            any = true;
        }
        if (any) {
            buffers.endBatch(IdentifyRenderer.QUADS_THROUGH_WALLS);
            buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
            buffers.endBatch(RenderType.debugQuads());
            buffers.endBatch(RenderType.lines());
        }
        pose.popPose();
    }

    private static void fillBox(Matrix4f m, VertexConsumer buffer, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        quad(m, buffer, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(m, buffer, r, g, b, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(m, buffer, r, g, b, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(m, buffer, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(m, buffer, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(m, buffer, r, g, b, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(Matrix4f m, VertexConsumer buffer, float r, float g, float b, float a, float... xyz) {
        for (int i = 0; i < 12; i += 3) {
            buffer.addVertex(m, xyz[i], xyz[i + 1], xyz[i + 2]).setColor(r, g, b, a);
        }
    }

    /** The alarm sound: a chime every two seconds for a warning, a two-tone siren for critical. */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        long time = mc.level.getGameTime();
        for (StatusBeaconBlockEntity beacon : StatusBeaconBlockEntity.CLIENT_LOADED) {
            AlarmSeverity shown = beacon.shown();
            if (shown == null || beacon.getLevel() != mc.level || beacon.isRemoved() || (beacon.style(shown) & StatusBeaconBlockEntity.SOUND) == 0) {
                continue;
            }
            BlockPos pos = beacon.getBlockPos();
            if (shown == AlarmSeverity.CRITICAL) {
                if (time % 10 == 0) {
                    float pitch = (time / 10) % 2 == 0 ? 1.6f : 1.2f;
                    mc.level.playLocalSound(pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 2f, pitch, false);
                }
            } else if (time % 40 == 0) {
                mc.level.playLocalSound(pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.5f, 1.2f, false);
            }
        }
    }
}
