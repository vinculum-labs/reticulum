package io.github.leadassimilator.reticulum.item;

import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A facade: right-click a cable or connector with it to make that block look like the facade's block. Crafted from a
 * cable and any full block.
 */
public class FacadeItem extends Item {
    public FacadeItem(Properties properties) {
        super(properties);
    }

    @Nullable
    public static BlockState state(ItemStack stack) {
        return stack.get(ModDataComponents.FACADE.get());
    }

    public static ItemStack of(BlockState state, int count) {
        ItemStack stack = new ItemStack(ModItems.FACADE.get(), count);
        stack.set(ModDataComponents.FACADE.get(), state);
        return stack;
    }

    /** Full, plain blocks (no block entity, drawn as a model) can be facades; glass and the like included. */
    public static boolean canBeFacade(BlockState state) {
        return !state.isAir() && !state.hasBlockEntity() && state.getRenderShape() == RenderShape.MODEL
                && Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    @Override
    public Component getName(ItemStack stack) {
        BlockState state = state(stack);
        return state == null ? super.getName(stack) : Component.translatable("item.reticulum.facade_of", state.getBlock().getName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.reticulum.facade").withStyle(ChatFormatting.GRAY));
    }
}
