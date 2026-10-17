package io.github.leadassimilator.reticulum.client;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.item.ToolMode;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;

/**
 * While the network tool or a block is held, the outline of a cable or connector narrows to the side the next click
 * acts on (the joint to toggle, or where the block will be placed), since the full-block hitbox alone doesn't show it.
 */
public final class ToolHighlight {
    private ToolHighlight() {
    }

    /** Worked out as the outline is extracted; drawn instead of the vanilla outline (see CustomBlockOutlineRenderer). */
    public static void onExtractOutline(ExtractBlockOutlineRenderStateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
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
        BlockPos pos = event.getBlockPos();
        BlockState state = event.getBlockState();
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return;
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Direction side = NetworkToolItem.targetSide(mc.player, state, pos, event.getHitResult().getDirection(), partialTick);
        Vec3 camera = event.getCamera().position();
        AABB arm = Topology.jointBox(side, state.getBlock() instanceof ConnectorBlock).move(pos).move(-camera.x, -camera.y, -camera.z);
        event.addCustomRenderer((outline, buffers, pose, translucentPass, level) -> {
            WorldOverlay.lineBox(pose, buffers.getBuffer(RenderTypes.lines()), arm, 0.3f, 0.8f, 1.0f, 0.9f);
            return true;
        });
    }
}
