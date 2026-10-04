package io.github.leadassimilator.reticulum.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.protocol.Screens;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Shows an alarm: gray, yellow (a warning) or red (critical) — its network's, or what a redstone signal says. */
public class StatusBeaconBlock extends BaseEntityBlock {
    public static final MapCodec<StatusBeaconBlock> CODEC = simpleCodec(StatusBeaconBlock::new);

    /** Network mode: a warning going off. */
    public static final int WARNING = 16;
    /** Network mode: a critical alarm going off. */
    public static final int CRITICAL = 17;
    /** Redstone mode: signal 0 has a look of its own, and shows it. */
    public static final int LEVEL_ZERO = 18;
    /**
     * What it shows: 0 nothing, 1 to 15 the redstone level (Redstone mode), {@link #LEVEL_ZERO}, {@link #WARNING} or
     * {@link #CRITICAL}.
     */
    public static final IntegerProperty SHOWN = IntegerProperty.create("shown", 0, 18);

    public StatusBeaconBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SHOWN, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SHOWN);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StatusBeaconBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.STATUS_BEACON.get(),
                level.isClientSide ? StatusBeaconBlockEntity::clientTick : StatusBeaconBlockEntity::serverTick);
    }

    /** In Redstone mode it follows the signal as it changes. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, from, moving);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof StatusBeaconBlockEntity beacon
                && beacon.mode() == StatusBeaconBlockEntity.Mode.REDSTONE) {
            StatusBeaconBlockEntity.update(server, pos, state, beacon);
        }
    }

    /** A dye recolors the look showing now (Network mode's warning or critical, or the redstone level's). */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.DyeItem dye)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof StatusBeaconBlockEntity beacon) {
            if (!Screens.mayChangeBeacon(serverPlayer, beacon)) {
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!beacon.recolorShown(dye.getDyeColor().getTextureDiffuseColor() & 0xFFFFFF)) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.reticulum.beacon_dye_nothing"), true);
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            Screens.openBeacon(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** A comparator reads what it shows: the redstone level it got, or 7 for a warning and 15 for critical. */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        int shown = state.getValue(SHOWN);
        return shown == WARNING ? 7 : shown == CRITICAL ? 15 : shown == LEVEL_ZERO ? 0 : shown;
    }
}
