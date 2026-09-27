package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared state of cables, connectors and controllers: the network this block belongs to and which of its
 * joints are cut. The network id is sticky: it survives losing the controller, so partly loaded or
 * controller-less sections keep their identity.
 */
public abstract class NetworkBlockEntity extends BlockEntity {
    @Nullable
    private UUID networkId;
    /** Joints the player cut on purpose. They persist when the neighbor is replaced. */
    private int manualCuts;
    /** Joints cut automatically (ambiguous placement, no access). Cleared when the neighbor goes away. */
    private int autoCuts;
    /** Whether topology has processed this block since it was placed. */
    private boolean registered;

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

    public boolean isCut(Direction side) {
        return ((manualCuts | autoCuts) & bit(side)) != 0;
    }

    public boolean isManualCut(Direction side) {
        return (manualCuts & bit(side)) != 0;
    }

    public void setManualCut(Direction side, boolean cut) {
        manualCuts = cut ? manualCuts | bit(side) : manualCuts & ~bit(side);
        setChanged();
    }

    public void setAutoCut(Direction side, boolean cut) {
        autoCuts = cut ? autoCuts | bit(side) : autoCuts & ~bit(side);
        setChanged();
    }

    public void clearCuts(Direction side) {
        manualCuts &= ~bit(side);
        autoCuts &= ~bit(side);
        setChanged();
    }

    public boolean isRegistered() {
        return registered;
    }

    public void setRegistered() {
        registered = true;
        setChanged();
    }

    protected static int bit(Direction side) {
        return 1 << side.get3DDataValue();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Blocks placed without a player (commands, structures, other mods) are joined up on the next tick.
        if (!registered && level != null && !level.isClientSide) {
            level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        networkId = tag.hasUUID("network") ? tag.getUUID("network") : null;
        manualCuts = tag.getByte("cuts");
        autoCuts = tag.getByte("auto_cuts");
        registered = tag.getBoolean("registered");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (networkId != null) {
            tag.putUUID("network", networkId);
        }
        tag.putByte("cuts", (byte) manualCuts);
        tag.putByte("auto_cuts", (byte) autoCuts);
        tag.putBoolean("registered", registered);
    }
}
