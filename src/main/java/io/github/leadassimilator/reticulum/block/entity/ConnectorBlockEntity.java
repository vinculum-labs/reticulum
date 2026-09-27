package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public class ConnectorBlockEntity extends NetworkBlockEntity {
    private UUID connectorId = UUID.randomUUID();
    /** Device sides the player switched off; those neighbors are not devices of this connector. */
    private int disabledDevices;

    public ConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONNECTOR.get(), pos, state);
    }

    public UUID connectorId() {
        return connectorId;
    }

    public boolean isDeviceEnabled(Direction side) {
        return (disabledDevices & bit(side)) == 0;
    }

    public void setDeviceEnabled(Direction side, boolean enabled) {
        disabledDevices = enabled ? disabledDevices & ~bit(side) : disabledDevices | bit(side);
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("connector")) {
            connectorId = tag.getUUID("connector");
        }
        disabledDevices = tag.getByte("disabled_devices");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("connector", connectorId);
        tag.putByte("disabled_devices", (byte) disabledDevices);
    }
}
