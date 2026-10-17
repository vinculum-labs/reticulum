package io.github.leadassimilator.reticulum.client;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.item.ToolMode;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * While the network tool or a block is held, the outline of a cable or connector narrows to the side the next click
 * acts on (the joint to toggle, or where the block will be placed), since the full-block hitbox alone doesn't show it.
 */
public final class ToolHighlight {
    private ToolHighlight() {
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // The tool in either hand; a block to place only in the main hand (that's the hand that places it).
        var tool = NetworkToolItem.held(mc.player);
        if (tool.isEmpty() && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            return;
        }
        // Link and Identify modes act on whole blocks, not on one joint.
        if (!tool.isEmpty() && NetworkToolItem.mode(tool) != ToolMode.CONFIGURE) {
            return;
        }
        BlockPos pos = event.getTarget().getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return;
        }
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Direction side = NetworkToolItem.targetSide(mc.player, state, pos, event.getTarget().getDirection(), partialTick);
        AABB arm = Topology.jointBox(side, state.getBlock() instanceof ConnectorBlock).move(pos);
        Vec3 camera = event.getCamera().getPosition();
        LevelRenderer.renderLineBox(event.getPoseStack(), event.getMultiBufferSource().getBuffer(RenderType.lines()),
                arm.move(-camera.x, -camera.y, -camera.z), 0.3f, 0.8f, 1.0f, 0.9f);
        event.setCanceled(true);
    }
}
