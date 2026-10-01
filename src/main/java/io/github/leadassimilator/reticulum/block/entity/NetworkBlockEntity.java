package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * Shared state of cables, connectors and controllers: the network this block belongs to and which of its
 * joints are disconnected. The network id is sticky: it survives losing the controller, so partly loaded or
 * controller-less sections keep their identity.
 */
public abstract class NetworkBlockEntity extends BlockEntity {
    @Nullable
    private UUID networkId;
    /** Joints the player disconnected on purpose. They persist when the neighbor is replaced. */
    private int manualDisconnects;
    /** Joints disconnected automatically (ambiguous placement, no access). Cleared when the neighbor goes away. */
    private int autoDisconnects;
    /** Whether topology has processed this block since it was placed. */
    private boolean registered;
    /** The block a cable or connector looks like (a facade), or null for its own look. */
    @Nullable
    private BlockState facade;

    /** Model data key the client model reads the facade from. */
    public static final ModelProperty<BlockState> FACADE = new ModelProperty<>();

    protected NetworkBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Nullable
    public UUID networkId() {
        return networkId;
    }

    public void setNetworkId(@Nullable UUID networkId) {
        this.networkId = networkId;
        setChanged();
    }

    public boolean isDisconnected(Direction side) {
        return ((manualDisconnects | autoDisconnects) & bit(side)) != 0;
    }

    public boolean isManuallyDisconnected(Direction side) {
        return (manualDisconnects & bit(side)) != 0;
    }

    public void setManuallyDisconnected(Direction side, boolean disconnected) {
        manualDisconnects = disconnected ? manualDisconnects | bit(side) : manualDisconnects & ~bit(side);
        setChanged();
    }

    public void setAutoDisconnected(Direction side, boolean disconnected) {
        autoDisconnects = disconnected ? autoDisconnects | bit(side) : autoDisconnects & ~bit(side);
        setChanged();
    }

    public void clearDisconnects(Direction side) {
        manualDisconnects &= ~bit(side);
        autoDisconnects &= ~bit(side);
        setChanged();
    }

    /** Loaded from data saved at another position (a copy), so it gets ids of its own. */
    protected boolean copied;

    public boolean isRegistered() {
        return registered;
    }

    public void setRegistered() {
        registered = true;
        setChanged();
    }

    @Nullable
    public BlockState facade() {
        return facade;
    }

    /** Sets or clears the facade and tells clients, which draw the block as the facade from then on. */
    public void setFacade(@Nullable BlockState facade) {
        this.facade = facade;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            // Light and sound come from the facade, so neighbors may need to look again.
            level.getLightEngine().checkBlock(worldPosition);
        }
    }

    @Override
    public ModelData getModelData() {
        return facade == null ? ModelData.EMPTY : ModelData.builder().with(FACADE, facade).build();
    }

    // Clients only need the facade.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (facade != null) {
            tag.put("facade", NbtUtils.writeBlockState(facade));
        }
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readFacade(tag, registries);
        clientFacadeChanged();
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        readFacade(packet.getTag(), registries);
        clientFacadeChanged();
    }

    private void readFacade(CompoundTag tag, HolderLookup.Provider registries) {
        facade = tag.contains("facade") ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("facade")) : null;
    }

    /** On the client: draw the block again with its new look, and keep track of it for the network tool's X-ray. */
    private void clientFacadeChanged() {
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL_IMMEDIATE);
            io.github.leadassimilator.reticulum.client.FacadeXray.track(net.minecraft.core.GlobalPos.of(level.dimension(), worldPosition), facade != null);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) {
            io.github.leadassimilator.reticulum.client.FacadeXray.track(net.minecraft.core.GlobalPos.of(level.dimension(), worldPosition), false);
        }
    }

    protected static int bit(Direction side) {
        return 1 << side.get3DDataValue();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Blocks placed without a player (commands, structures, other mods) are connected up at the start of the next
        // server tick. (A scheduled block tick would be lost when this runs while its chunk is still loading.)
        if (!registered && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            io.github.leadassimilator.reticulum.topology.Topology.queue(serverLevel, worldPosition);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        networkId = tag.hasUUID("network") ? tag.getUUID("network") : null;
        manualDisconnects = tag.getByte("disconnected");
        autoDisconnects = tag.getByte("auto_disconnected");
        // Registered where it was saved; data copied somewhere else (ctrl+pick-block, /clone, a structure) is a new
        // block there: it joins whatever it is placed against, rather than claiming the original's network and ids.
        boolean wasRegistered = tag.getBoolean("registered");
        registered = wasRegistered && (!tag.contains("registered_at") || tag.getLong("registered_at") == worldPosition.asLong());
        copied = wasRegistered && !registered;
        if (copied) {
            networkId = null;
        }
        readFacade(tag, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (networkId != null) {
            tag.putUUID("network", networkId);
        }
        tag.putByte("disconnected", (byte) manualDisconnects);
        tag.putByte("auto_disconnected", (byte) autoDisconnects);
        tag.putBoolean("registered", registered);
        tag.putLong("registered_at", worldPosition.asLong());
        if (facade != null) {
            tag.put("facade", NbtUtils.writeBlockState(facade));
        }
    }
}
