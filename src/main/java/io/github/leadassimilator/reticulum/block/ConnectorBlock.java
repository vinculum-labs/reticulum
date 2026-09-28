package io.github.leadassimilator.reticulum.block;

import java.util.Map;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

public class ConnectorBlock extends LinkedBlock {
    public static final MapCodec<ConnectorBlock> CODEC = simpleCodec(ConnectorBlock::new);
    static final Map<Direction, EnumProperty<Link>> LINKS = create(Link.values());

    public ConnectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConnectorBlockEntity(pos, state);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // direction points from the block being powered towards this connector.
        return level.getBlockEntity(pos) instanceof ConnectorBlockEntity be ? be.power(direction.getOpposite()) : 0;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        // A neighbor becoming or ceasing to be a device does not always change its shape, so rescan here too.
        if (level instanceof ServerLevel serverLevel) {
            Topology.rescanConnector(serverLevel, pos);
            Topology.refreshState(serverLevel, pos);
            if (serverLevel.getBlockEntity(pos) instanceof ConnectorBlockEntity be) {
                be.refreshEndpoints();
            }
        }
    }
}
