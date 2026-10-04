package io.github.leadassimilator.reticulum.client;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A Status Beacon's beam, in its look's color, up to the sky and down to the bottom of the world (or to a solid block). */
public class StatusBeaconRenderer implements BlockEntityRenderer<StatusBeaconBlockEntity> {
    public static final int WARNING_COLOR = 0xFFFFB020;
    public static final int CRITICAL_COLOR = 0xFFFF3020;

    public StatusBeaconRenderer(BlockEntityRendererProvider.Context context) {
    }

    public static void register(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(io.github.leadassimilator.reticulum.registry.ModBlockEntities.STATUS_BEACON.get(), StatusBeaconRenderer::new);
    }

    @Override
    public void render(StatusBeaconBlockEntity beacon, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        io.github.leadassimilator.reticulum.block.entity.BeaconLook look = beacon.look();
        if (look == null || !look.has(io.github.leadassimilator.reticulum.block.entity.BeaconLook.BEAM) || beacon.getLevel() == null) {
            return;
        }
        int color = 0xFF000000 | look.color();
        long time = beacon.getLevel().getGameTime();
        if (beacon.beamUp() > 0) {
            BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1f, time, 1, beacon.beamUp(), color, 0.2f, 0.25f);
        }
        if (beacon.beamDown() > 0) {
            BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1f, time, -beacon.beamDown(), beacon.beamDown(), color, 0.2f, 0.25f);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(StatusBeaconBlockEntity beacon) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    /** Seen from anywhere along its column, like a vanilla beacon's beam. */
    @Override
    public boolean shouldRender(StatusBeaconBlockEntity beacon, Vec3 camera) {
        return Vec3.atCenterOf(beacon.getBlockPos()).multiply(1, 0, 1).closerThan(camera.multiply(1, 0, 1), getViewDistance());
    }

    @Override
    public AABB getRenderBoundingBox(StatusBeaconBlockEntity beacon) {
        return AABB.INFINITE;
    }
}
