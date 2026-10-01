package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration;
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
    /** The AE2 endpoints (integration.ae2.MeEndpoints) when AE2 is installed; typed loosely so AE2 is optional. */
    @Nullable
    private Object meEndpoints;

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
    public Object meEndpoints() {
        return meEndpoints;
    }

    public void setMeEndpoints(Object endpoints) {
        meEndpoints = endpoints;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (Mods.AE2 && level != null && !level.isClientSide) {
            Ae2Integration.onLoad(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (Mods.AE2) {
            Ae2Integration.destroy(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (Mods.AE2) {
            Ae2Integration.destroy(this);
        }
    }

    /** A neighbor changed or a device side was switched: ME endpoints follow the AE2 blocks around the connector. */
    public void refreshEndpoints() {
        if (Mods.AE2 && level != null && !level.isClientSide) {
            Ae2Integration.refresh(this);
        }
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
        if (tag.hasUUID("connector") && !copied) {
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
