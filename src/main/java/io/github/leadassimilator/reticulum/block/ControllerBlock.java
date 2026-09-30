package io.github.leadassimilator.reticulum.block;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.item.AntennaItem;
import io.github.leadassimilator.reticulum.item.ConnectorItem;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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
    /** The antenna tier installed (0: none), shown on the top face. The block entity holds the truth and sets this. */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty ANTENNA =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("antenna", 0, 3);

    public ControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ANTENNA, 0));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ANTENNA);
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
        if (stack.getItem() instanceof NetworkToolItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof AntennaItem antenna && !player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) {
                installAntenna(serverPlayer, level, pos, stack, antenna);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.getItem() instanceof ConnectorItem && !player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) {
                bindStack(serverPlayer, level, pos, stack);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** Puts the antenna in the controller, handing back the one it had. */
    private static void installAntenna(ServerPlayer player, Level level, BlockPos pos, ItemStack stack, AntennaItem antenna) {
        if (!(level.getBlockEntity(pos) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = NetworkManager.network(player.server, controller.networkId());
        if (network != null && !Security.canEdit(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_edit_access"), true);
            return;
        }
        if (controller.antennaTier() == antenna.tier()) {
            player.displayClientMessage(Component.translatable("message.reticulum.antenna_same"), true);
            return;
        }
        ItemStack old = ModItems.antenna(controller.antennaTier());
        controller.setAntennaTier(antenna.tier());
        stack.consume(1, player);
        if (!old.isEmpty() && !player.getInventory().add(old)) {
            player.drop(old, false);
        }
        if (network != null) {
            NetworkManager.changed(player.server, network);
        }
        player.displayClientMessage(Component.translatable("message.reticulum.antenna_installed", AntennaItem.range(antenna.tier())), true);
    }

    /** Takes the antenna out (the network tool in Configure mode, sneaking), handing it back. */
    public static void removeAntenna(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ControllerBlockEntity controller)) {
            return;
        }
        if (controller.antennaTier() == 0) {
            player.displayClientMessage(Component.translatable("message.reticulum.antenna_none_installed"), true);
            return;
        }
        Network network = NetworkManager.network(player.server, controller.networkId());
        if (network != null && !Security.canEdit(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_edit_access"), true);
            return;
        }
        ItemStack old = ModItems.antenna(controller.antennaTier());
        controller.setAntennaTier(0);
        if (!player.getAbilities().instabuild && !player.getInventory().add(old)) {
            player.drop(old, false);
        }
        if (network != null) {
            NetworkManager.changed(player.server, network);
        }
        player.displayClientMessage(Component.translatable("message.reticulum.antenna_removed"), true);
    }

    /** Binds a whole stack of connectors to this network: placed where no cable decides, they join it wirelessly. */
    private static void bindStack(ServerPlayer player, Level level, BlockPos pos, ItemStack stack) {
        if (!(level.getBlockEntity(pos) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = NetworkManager.network(player.server, controller.networkId());
        if (network == null || !Security.canUse(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_access", network == null ? "?" : network.name()), true);
            return;
        }
        stack.set(ModDataComponents.NETWORK_ID.get(), network.id());
        stack.set(ModDataComponents.NETWORK_NAME.get(), network.name());
        player.displayClientMessage(Component.translatable("message.reticulum.stack_bound", stack.getCount(), network.name()), true);
    }

    /** The antenna comes out when the controller is broken. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ControllerBlockEntity controller && controller.antennaTier() > 0) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), ModItems.antenna(controller.antennaTier()));
            controller.setAntennaTier(0);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControllerBlockEntity(pos, state);
    }
}
