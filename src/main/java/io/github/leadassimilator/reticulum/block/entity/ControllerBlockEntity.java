package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.state.BlockState;

public class ControllerBlockEntity extends NetworkBlockEntity {
    /** Network remembered by the controller item this block was placed from; consumed by topology. */
    @Nullable
    private UUID itemNetworkId;

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONTROLLER.get(), pos, state);
    }

    @Nullable
    public UUID takeItemNetworkId() {
        UUID id = itemNetworkId;
        itemNetworkId = null;
        return id;
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        itemNetworkId = input.get(ModDataComponents.NETWORK_ID.get());
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (networkId() != null) {
            components.set(ModDataComponents.NETWORK_ID.get(), networkId());
        }
    }
}
