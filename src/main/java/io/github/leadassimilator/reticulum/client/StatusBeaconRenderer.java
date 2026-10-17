package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** A Status Beacon's beam, in its look's color, up to the sky and down to the bottom of the world (or to a solid block). */
public class StatusBeaconRenderer implements BlockEntityRenderer<StatusBeaconBlockEntity, StatusBeaconRenderer.State> {
    public static final int WARNING_COLOR = 0xFFFFB020;
    public static final int CRITICAL_COLOR = 0xFFFF3020;

    /** What a frame draws of a beacon. */
    public static final class State extends BlockEntityRenderState {
        boolean beam;
        int color;
        int up;
        int down;
        float animationTime;
    }

    public StatusBeaconRenderer(BlockEntityRendererProvider.Context context) {
    }

    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.STATUS_BEACON.get(), StatusBeaconRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(StatusBeaconBlockEntity beacon, State state, float partialTick, Vec3 camera,
            @Nullable ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beacon, state, partialTick, camera, breakProgress);
        BeaconLook look = beacon.look();
        state.beam = look != null && look.has(BeaconLook.BEAM) && beacon.getLevel() != null;
        if (state.beam) {
            state.color = 0xFF000000 | look.color();
            state.up = beacon.beamUp();
            state.down = beacon.beamDown();
            // As a vanilla beacon's beam moves.
            state.animationTime = Math.floorMod(beacon.getLevel().getGameTime(), 40) + partialTick;
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.beam) {
            return;
        }
        if (state.up > 0) {
            BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1f, state.animationTime, 1, state.up, state.color, 0.2f, 0.25f);
        }
        if (state.down > 0) {
            BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1f, state.animationTime, -state.down, state.down, state.color,
                    0.2f, 0.25f);
        }
    }

    @Override
    public boolean shouldRenderOffScreen() {
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
