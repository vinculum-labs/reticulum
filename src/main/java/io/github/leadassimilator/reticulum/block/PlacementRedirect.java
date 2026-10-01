package io.github.leadassimilator.reticulum.block;

import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Cables and connectors have a full-block hitbox but render much smaller, so the face the crosshair hits is often
 * not the face the player sees. When a block is placed against one (sneak + right-click), this places it on the side the player is
 * actually aiming at (the same side the outline shows). Runs on both sides so the client prediction matches, and last,
 * so a claim mod that refuses the click has already cancelled it.
 */
public final class PlacementRedirect {
    private PlacementRedirect() {
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        // Without sneaking, right-click opens the block's screen instead of placing.
        if (!player.isSecondaryUseActive() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return;
        }
        Direction face = event.getFace() == null ? Direction.UP : event.getFace();
        Direction side = NetworkToolItem.targetSide(player, state, pos, face, 1.0f);
        if (side == face) {
            return;
        }
        Vec3 onFace = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.5));
        BlockHitResult hit = new BlockHitResult(onFace, side, pos, false);
        // Through the stack, not the item: that is where adventure mode's can_place_on and NeoForge's placement event
        // (which claim mods use) apply.
        InteractionResult result = stack.useOn(new UseOnContext(player, event.getHand(), hit));
        event.setCanceled(true);
        event.setCancellationResult(result);
    }
}
