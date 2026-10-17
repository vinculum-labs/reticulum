package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.block.Orientation;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.EmptyResourceHandler;
import net.neoforged.neoforge.transfer.energy.EmptyEnergyHandler;

public class ConnectorBlockEntity extends NetworkBlockEntity {
    private UUID connectorId = UUID.randomUUID();
    /** Device sides the player switched off; those neighbors are not devices of this connector. */
    private int disabledDevices;
    /** Redstone given out towards each side (Redstone out nodes); not saved, the network sets it every tick. */
    private final byte[] power = new byte[6];
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

    /** Whether this connector shows a device on that side: one there, and the side attached. */
    public boolean facesDevice(@Nullable Direction side) {
        return side != null && getBlockState().getValue(ConnectorBlock.LINKS.get(side)) == Link.DEVICE;
    }

    /**
     * Empty handlers on the sides facing a device, and only there: cables that show an arm toward what has a handler
     * (Mekanism's) then meet the connector's, and a detached side offers nothing. They hold nothing and take nothing;
     * the connector moves things through the device's own handlers.
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, ModBlockEntities.CONNECTOR.get(),
                (connector, side) -> connector.facesDevice(side) ? EmptyResourceHandler.instance() : null);
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, ModBlockEntities.CONNECTOR.get(),
                (connector, side) -> connector.facesDevice(side) ? EmptyResourceHandler.instance() : null);
        event.registerBlockEntity(Capabilities.Energy.BLOCK, ModBlockEntities.CONNECTOR.get(),
                (connector, side) -> connector.facesDevice(side) ? EmptyEnergyHandler.INSTANCE : null);
    }

    @Override
    public void keepSides(Orientation was, Orientation now) {
        disabledDevices = keptSides(disabledDevices, was, now);
        super.keepSides(was, now);
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
        if (Mods.ME != null && level != null && !level.isClientSide()) {
            Mods.ME.onLoad(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (Mods.ME != null) {
            Mods.ME.destroy(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (Mods.ME != null) {
            Mods.ME.destroy(this);
        }
    }

    /** A neighbor changed or a device side was switched: ME endpoints follow the AE2 blocks around the connector. */
    public void refreshEndpoints() {
        if (Mods.ME != null && level != null && !level.isClientSide()) {
            Mods.ME.refresh(this);
        }
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
    protected void loadAdditional(ValueInput tag) {
        super.loadAdditional(tag);
        UUID saved = tag.read("connector", UUIDUtil.CODEC).orElse(null);
        if (saved != null && (!copied || isOriginal(saved))) {
            connectorId = saved;
        } else if (copied) {
            // A copy (pick-block, /clone, a pasted structure): a new connector, on no network until placed against one.
            setNetworkId(null);
        }
        disabledDevices = tag.getByteOr("disabled_devices", (byte) 0);
    }

    /**
     * Whether this connector, saved under {@code id} somewhere else, is that connector moved here rather than a copy of
     * it. Its network's record decides: lifted by a piston or contraption, it is marked moving; otherwise the block at
     * the recorded place still being that connector (or that place not loaded, or no record) makes this a copy. Order,
     * timing and restarts don't matter, and two live connectors never share an id.
     */
    private boolean isOriginal(UUID id) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        var network = NetworkManager.network(server.getServer(), networkId());
        var record = network == null ? null : network.connector(id);
        if (record == null) {
            return false;
        }
        if (record.moving()) {
            return true;
        }
        var there = server.getServer().getLevel(record.pos().dimension());
        if (there == null || !there.isLoaded(record.pos().pos())) {
            return false;
        }
        return !(there.getBlockEntity(record.pos().pos()) instanceof ConnectorBlockEntity other && id.equals(other.connectorId()));
    }

    @Override
    protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        tag.store("connector", UUIDUtil.CODEC, connectorId);
        tag.putByte("disabled_devices", (byte) disabledDevices);
    }
}
