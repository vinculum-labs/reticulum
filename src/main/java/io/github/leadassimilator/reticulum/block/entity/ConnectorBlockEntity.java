package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public class ConnectorBlockEntity extends NetworkBlockEntity {
    private UUID connectorId = UUID.randomUUID();
    /** Device sides the player switched off; those neighbors are not devices of this connector. */
    private int disabledDevices;
    /** Redstone given out towards each side (Redstone out nodes); not saved, the network sets it every tick. */
    private final byte[] power = new byte[6];
    /** Network of the bound connector item this block was placed from; consumed by topology. */
    @Nullable
    private UUID itemNetworkId;

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

    /** Redstone this connector gives to the block on that side. */
    public int power(Direction toward) {
        return power[toward.get3DDataValue()];
    }

    /** Returns whether it changed, so the neighbors need an update. */
    public boolean setPower(Direction toward, int level) {
        byte next = (byte) Math.max(0, Math.min(15, level));
        if (power[toward.get3DDataValue()] == next) {
            return false;
        }
        power[toward.get3DDataValue()] = next;
        return true;
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
