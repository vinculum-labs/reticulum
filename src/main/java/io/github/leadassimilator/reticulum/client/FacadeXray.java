package io.github.leadassimilator.reticulum.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While the player holds the network tool, cables and connectors hidden behind facades show as a wireframe through
 * walls, so hidden networks can still be traced.
 */
public final class FacadeXray {
    private static final double RANGE = 48;
    /** Client-side positions (with their dimension) of cables and connectors that have a facade. */
    private static final Set<GlobalPos> FACADED = ConcurrentHashMap.newKeySet();

    private FacadeXray() {
    }

    public static void track(GlobalPos pos, boolean facaded) {
        if (facaded) {
            FACADED.add(pos);
        } else {
            FACADED.remove(pos);
        }
    }

    public static void clear() {
        FACADED.clear();
    }

    public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (FACADED.isEmpty() || mc.player == null || mc.level == null
                || NetworkToolItem.held(mc.player).isEmpty()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(IdentifyRenderer.LINES_THROUGH_WALLS);
        for (GlobalPos where : FACADED) {
            BlockPos pos = where.pos();
            if (!where.dimension().equals(mc.level.dimension()) || camera.distanceToSqr(Vec3.atCenterOf(pos)) > RANGE * RANGE
                    || !mc.level.isLoaded(pos)) {
                continue;
            }
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof LinkedBlock block)) {
                // Gone without telling us (replaced while unloaded, say): forget it.
                FACADED.remove(where);
                continue;
            }
            boolean connector = block instanceof ConnectorBlock;
            // The cable's core, and an arm towards each side it joins, as drawn without the facade.
            ShapeRenderer.renderLineBox(pose, lines, Topology.coreBox(connector).move(pos), 0.55f, 0.85f, 1f, 0.9f);
            for (Direction side : Direction.values()) {
                EnumProperty<Link> property = block.linkProperties().get(side);
                if (property != null && state.getValue(property) != Link.NONE) {
                    AABB arm = Topology.jointBox(side, connector).move(pos);
                    ShapeRenderer.renderLineBox(pose, lines, arm, 0.55f, 0.85f, 1f, 0.9f);
                }
            }
        }
        buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
        pose.popPose();
    }
}
