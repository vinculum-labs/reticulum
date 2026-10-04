package io.github.leadassimilator.reticulum.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import io.github.leadassimilator.reticulum.protocol.Screens;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.util.LowercaseName;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Shows an alarm: gray, yellow (a warning) or red (critical) — its network's, or what a redstone signal says. */
public class StatusBeaconBlock extends BaseEntityBlock {
    public static final MapCodec<StatusBeaconBlock> CODEC = simpleCodec(StatusBeaconBlock::new);

    /** The worst alarm going off on its network, as shown. */
    public enum Shown implements LowercaseName {
        NONE, WARNING, CRITICAL;

        public static Shown of(@Nullable AlarmSeverity severity) {
            return severity == null ? NONE : severity == AlarmSeverity.CRITICAL ? CRITICAL : WARNING;
        }

        @Nullable
        public AlarmSeverity severity() {
            return this == NONE ? null : this == CRITICAL ? AlarmSeverity.CRITICAL : AlarmSeverity.WARNING;
        }
    }

    public static final EnumProperty<Shown> ALARM = EnumProperty.create("alarm", Shown.class);

    public StatusBeaconBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ALARM, Shown.NONE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ALARM);
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

    /** A comparator reads 0 (nothing), 7 (a warning) or 15 (critical). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return switch (state.getValue(ALARM)) {
            case NONE -> 0;
            case WARNING -> 7;
            case CRITICAL -> 15;
        };
    }
}
