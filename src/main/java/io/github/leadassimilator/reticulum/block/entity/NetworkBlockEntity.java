package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.FacadeLight;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.Orientation;
import io.github.leadassimilator.reticulum.client.FacadeXray;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
    /**
     * The block a cable or connector looks like (a facade), or null for its own look. Kept as laid out, as the cuts are,
     * so a facade turns with its block (see {@link #facade()}).
     */
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

    /** The network the item it was placed from carried; taken once, when it joins. */
    @Nullable
    private UUID itemNetworkId;

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

    /** Loaded from data saved at another position (a copy), so it gets ids of its own. */
    protected boolean copied;
    /**
     * Arrived from somewhere else and not connected up yet. Unlike {@link #copied} it is saved: a block set down by a
     * contraption whose chunk unloads (or the server stops) before it connects up still connects up as one moved in.
     */
    private boolean arrived;

    public boolean isRegistered() {
        return registered;
    }

    /** Arrived with data saved somewhere else: moved here by a piston or contraption, or copied. */
    public boolean arrivedFromElsewhere() {
        return arrived;
    }

    public void setRegistered() {
        registered = true;
        setChanged();
    }

    /** The facade as it stands in the world: turned as its block has been since it was put on. */
    @Nullable
    public BlockState facade() {
        return facade == null ? null : orientation().toWorld(facade);
    }

    private Orientation orientation() {
        BlockState state = getBlockState();
        return state.hasProperty(LinkedBlock.ORIENTATION)
                ? state.getValue(LinkedBlock.ORIENTATION)
                : Orientation.TURN_0;
    }

    /** Sets or clears the facade and tells clients, which draw the block as the facade from then on. */
    public void setFacade(@Nullable BlockState facade) {
        this.facade = facade == null ? null : orientation().toLaid(facade);
        lightChanged();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            // Light and sound come from the facade, so neighbors may need to look again.
            level.getLightEngine().checkBlock(worldPosition);
        }
    }

    @Override
    public ModelData getModelData() {
        return facade == null ? ModelData.EMPTY : ModelData.builder().with(FACADE, facade()).build();
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
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        readFacade(packet.getTag(), registries);
        clientFacadeChanged();
    }

    private void readFacade(CompoundTag tag, HolderLookup.Provider registries) {
        facade = tag.contains("facade") ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("facade")) : null;
        // A block from a mod since removed reads as air: no facade (an invisible block with a full outline otherwise).
        if (facade != null && facade.isAir()) {
            facade = null;
        }
        lightChanged();
    }

    /** Tells the light engine (which can't read block entities) the light its facade gives. */
    private void lightChanged() {
        FacadeLight.set(level, worldPosition, facade == null ? 0 : facade.getLightEmission());
    }

    /** On the client: draw the block again with its new look, and keep track of it for the network tool's X-ray. */
    private void clientFacadeChanged() {
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL_IMMEDIATE);
            FacadeXray.track(GlobalPos.of(level.dimension(), worldPosition), facade != null);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        FacadeLight.set(level, worldPosition, 0);
        if (level != null && level.isClientSide) {
            FacadeXray.track(GlobalPos.of(level.dimension(), worldPosition), false);
        }
    }

    /**
     * Turned in place from {@code was} to {@code now}: what it keeps by side is moved so that it stays on the same sides
     * of the world.
     */
    public void keepSides(Orientation was, Orientation now) {
        manualDisconnects = keptSides(manualDisconnects, was, now);
        autoDisconnects = keptSides(autoDisconnects, was, now);
        if (facade != null) {
            // Its facade stays as it stood too.
            facade = now.toLaid(was.toWorld(facade));
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        setChanged();
    }

    /** {@code mask}, kept by sides as laid out under {@code was}, kept by sides as laid out under {@code now} instead. */
    protected static int keptSides(int mask, Orientation was, Orientation now) {
        int out = 0;
        for (Direction side : Direction.values()) {
            if ((mask & (1 << was.toLaid(side).get3DDataValue())) != 0) {
                out |= 1 << now.toLaid(side).get3DDataValue();
            }
        }
        return out;
    }

    /**
     * The bit kept for a side of the world: per-side data is kept by the sides as they were laid out, so it follows the
     * block when a structure, schematic or contraption turns it (see {@link io.github.leadassimilator.reticulum.block.Orientation}).
     */
    protected int bit(Direction side) {
        BlockState state = getBlockState();
        Direction laid = state.hasProperty(LinkedBlock.ORIENTATION)
                ? state.getValue(LinkedBlock.ORIENTATION).toLaid(side) : side;
        return 1 << laid.get3DDataValue();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Loaded with its chunk (before it had a world): its facade's light is told now.
        lightChanged();
        // Blocks placed without a player (commands, structures, other mods) are connected up at the start of the next
        // server tick. (A scheduled block tick would be lost when this runs while its chunk is still loading.)
        if (!registered && level instanceof ServerLevel serverLevel) {
            Topology.queue(serverLevel, worldPosition);
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
        boolean atOrigin = !tag.contains("registered_at") || tag.getLong("registered_at") == worldPosition.asLong();
        // Saved somewhere else (moved, or copied): it connects up again where it is now, joining what it touches, or
        // staying on the network it carries where it touches nothing. Whether it is a copy only matters for a
        // connector's id (see ConnectorBlockEntity).
        registered = wasRegistered && atOrigin;
        copied = wasRegistered && !atOrigin;
        arrived = copied || (!wasRegistered && tag.getBoolean("arrived"));
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
        if (arrived && !registered) {
            tag.putBoolean("arrived", true);
        }
        tag.putLong("registered_at", worldPosition.asLong());
        if (facade != null) {
            tag.put("facade", NbtUtils.writeBlockState(facade));
        }
    }
}
