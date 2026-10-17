package io.github.leadassimilator.reticulum.gametest;

import javax.annotation.Nullable;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** AE2 helpers for GameTests; only loaded when AE2 is present. */
final class Ae2TestSupport {
    private Ae2TestSupport() {
    }

    static Block block(String path) {
        return BuiltInRegistries.BLOCK.get(Identifier.fromNamespaceAndPath("ae2", path));
    }

    /** Uses an AE2 item (a cable or a part) on a block face, as a player would. */
    static InteractionResult use(ServerLevel level, String item, BlockPos clicked, Direction face) {
        var player = FakePlayerFactory.getMinecraft(level);
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath("ae2", item)));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(
                Vec3.atCenterOf(clicked).relative(face, 0.5), face, clicked, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    @Nullable
    static IGrid grid(Level level, BlockPos pos, Direction side) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        IGridNode node = host == null ? null : host.getGridNode(side);
        return node == null ? null : node.getGrid();
    }

    /** Whether the AE2 blocks at the two positions (seen from the given sides) are on one AE2 network. */
    static boolean sameGrid(Level level, BlockPos a, Direction sideA, BlockPos b, Direction sideB) {
        IGrid gridA = grid(level, a, sideA);
        return gridA != null && gridA == grid(level, b, sideB);
    }
}
