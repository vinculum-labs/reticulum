package io.github.leadassimilator.reticulum.block;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ControllerBlock extends NetworkBlock {
    public static final MapCodec<ControllerBlock> CODEC = simpleCodec(ControllerBlock::new);

    public ControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /**
     * Like a barrel: right-click opens the controller whatever is in hand, and sneak + right-click places a block
     * (vanilla skips the block when sneaking with an item). The network tool still acts on the controller's joints.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hitResult) {
        return stack.getItem() instanceof NetworkToolItem ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControllerBlockEntity(pos, state);
    }
}
