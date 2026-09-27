package io.github.leadassimilator.reticulum.item;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The network tool. Phase 1 has only Configure mode: right-click a joint of a cable or connector to connect or disconnect it,
 * or a connector's device side to attach or detach that device. Link and Identify modes come later.
 */
public class NetworkToolItem extends Item {
    public NetworkToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof NetworkBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            Direction side = targetSide(player, level.getBlockState(context.getClickedPos()), context.getClickedPos(),
                    context.getClickedFace(), 1.0f);
            player.displayClientMessage(Topology.toggle(level, context.getClickedPos(), side, player), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /** The side of a network block the player is aiming at. Used for both the click and the client outline. */
    public static Direction targetSide(Player player, BlockState state, BlockPos pos, Direction face, float partialTick) {
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return face;
        }
        Vec3 from = player.getEyePosition(partialTick);
        Vec3 to = from.add(player.getViewVector(partialTick).scale(player.blockInteractionRange() + 1));
        return Topology.targetSide(pos, state.getBlock() instanceof ConnectorBlock, from, to, face);
    }
}
