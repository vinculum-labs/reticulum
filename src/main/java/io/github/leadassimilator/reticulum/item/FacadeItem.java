package io.github.leadassimilator.reticulum.item;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.client.screen.FacadeScreen;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A facade: blank until painted to look like a block. Right-click to pick the block for the whole stack in hand (any
 * block, owned or not; JEI can drag one in), or sneak + right-click a block in the world to copy exactly how it looks.
 * Right-click a cable or connector with a painted one to put it on.
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
        try {
            return !state.isAir() && !state.hasBlockEntity() && state.getRenderShape() == RenderShape.MODEL
                    && Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (RuntimeException e) {
            // Some blocks' shapes need a real world around them; those can't be facades.
            return false;
        }
    }

    /** Makes a whole stack look like {@code state}, or blank when null. False (and unchanged) when it can't be a facade. */
    public static boolean paint(ItemStack stack, @Nullable BlockState state) {
        if (state == null) {
            stack.remove(ModDataComponents.FACADE.get());
            return true;
        }
        if (!canBeFacade(state)) {
            return false;
        }
        stack.set(ModDataComponents.FACADE.get(), state);
        return true;
    }

    /** The facade screen's choice, for the stack in that hand. */
    public static void paint(ServerPlayer player, Payloads.PaintFacade payload) {
        ItemStack stack = player.getItemInHand(payload.hand());
        if (!(stack.getItem() instanceof FacadeItem)) {
            return;
        }
        BlockState state = payload.block().map(Block::defaultBlockState).orElse(null);
        if (!paint(stack, state)) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_facade_block", state.getBlock().getName()), true);
        }
    }

    /** Opens the screen to pick what the stack in hand looks like. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            FacadeScreen.open(hand);
        }
        return InteractionResult.SUCCESS;
    }

    /** Sneak + right-click a block: the stack takes on exactly how it looks (a faced cable gives its facade). */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockState state = level.getBlockEntity(context.getClickedPos()) instanceof NetworkBlockEntity be && be.facade() != null
                ? be.facade()
                : level.getBlockState(context.getClickedPos());
        if (!level.isClientSide) {
            player.displayClientMessage(paint(context.getItemInHand(), state)
                    ? Component.translatable("message.reticulum.facade_copied", state.getBlock().getName())
                    : Component.translatable("message.reticulum.not_facade_block", state.getBlock().getName()), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        BlockState state = state(stack);
        return state == null ? Component.translatable("item.reticulum.facade_blank") : Component.translatable("item.reticulum.facade_of", state.getBlock().getName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        if (state(stack) != null) {
            tooltip.accept(Component.translatable("tooltip.reticulum.facade").withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(Component.translatable("tooltip.reticulum.facade_paint").withStyle(ChatFormatting.GRAY));
    }
}
