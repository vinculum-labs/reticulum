package io.github.leadassimilator.reticulum.client;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * While the network tool is held, the outline of a cable or connector narrows to the joint the next click will
 * toggle, since the full-block hitbox alone doesn't show which arm is targeted.
 */
public final class ToolHighlight {
    private ToolHighlight() {
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.player.getMainHandItem().getItem() instanceof NetworkToolItem)) {
            return;
        }
        BlockPos pos = event.getTarget().getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return;
        }
        Direction side = Topology.sideFromHit(event.getTarget());
        double core = state.getBlock() instanceof ConnectorBlock ? 4 / 16.0 : 6 / 16.0;
        AABB arm = armBox(side, core).move(pos);
        Vec3 camera = event.getCamera().getPosition();
        LevelRenderer.renderLineBox(event.getPoseStack(), event.getMultiBufferSource().getBuffer(RenderType.lines()),
                arm.move(-camera.x, -camera.y, -camera.z), 0.3f, 0.8f, 1.0f, 0.9f);
        event.setCanceled(true);
    }

    /** The 4-pixel-wide arm from the block's core (inset {@code core} from each edge) out to the given side. */
    private static AABB armBox(Direction side, double core) {
        double lo = 6 / 16.0;
        double hi = 10 / 16.0;
        double[] min = {lo, lo, lo};
        double[] max = {hi, hi, hi};
        int axis = side.getAxis().ordinal();
        if (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[axis] = 1 - core;
            max[axis] = 1;
        } else {
            min[axis] = 0;
            max[axis] = core;
        }
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }
}
