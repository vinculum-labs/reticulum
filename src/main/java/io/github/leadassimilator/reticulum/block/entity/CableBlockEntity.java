package io.github.leadassimilator.reticulum.block.entity;

import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class CableBlockEntity extends NetworkBlockEntity {
    public CableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CABLE.get(), pos, state);
    }
}
