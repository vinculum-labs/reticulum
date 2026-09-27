package io.github.leadassimilator.reticulum.block;

import java.util.Map;

import com.mojang.serialization.MapCodec;

import io.github.leadassimilator.reticulum.block.entity.CableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

public class CableBlock extends LinkedBlock {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    static final Map<Direction, EnumProperty<Link>> LINKS = create(Link.NONE, Link.CABLE);

    public CableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableBlockEntity(pos, state);
    }
}
