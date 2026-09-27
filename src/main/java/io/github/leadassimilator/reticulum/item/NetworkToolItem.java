package io.github.leadassimilator.reticulum.item;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The network tool. Phase 1 has only Configure mode: right-click a joint of a cable or connector to cut or join it,
 * or a connector's device side to switch that device on or off. Link and Identify modes come later.
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
            BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
            player.displayClientMessage(Topology.toggle(level, context.getClickedPos(), Topology.sideFromHit(hit), player), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
}
