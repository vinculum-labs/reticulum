package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.world.level.block.state.BlockState;

public class ControllerBlockEntity extends NetworkBlockEntity {
    /** Installed antenna upgrade, 0 (none) to 3. */
    private int antennaTier;
    /** FE held to run the network (only used when the server's power preset charges for it). */
    private long energy;

    /** Takes FE from anything that pushes into the controller; gives none back. */
    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            // Power free (no preset charging for it): it takes nothing, so energy isn't soaked up for no use (and a
            // broken controller stacks with fresh ones).
            if (!io.github.leadassimilator.reticulum.engine.Power.current().enabled()) {
                return 0;
            }
            long room = Math.max(0, capacity() - energy);
            int accepted = (int) Math.min(room, Math.max(0, toReceive));
            if (!simulate && accepted > 0) {
                energy += accepted;
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, energy);
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, capacity());
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONTROLLER.get(), pos, state);
    }

    public IEnergyStorage energyStorage() {
        return energyStorage;
    }

    public long energy() {
        return energy;
    }

    public static long capacity() {
        return io.github.leadassimilator.reticulum.ReticulumConfig.CONTROLLER_CAPACITY.getAsInt();
    }

    /** Takes {@code amount} FE if it is all there; returns whether it was. */
    public boolean draw(long amount) {
        if (amount <= 0) {
            return true;
        }
        if (energy < amount) {
            return false;
        }
        energy -= amount;
        setChanged();
        return true;
    }

    public int antennaTier() {
        return antennaTier;
    }

    public void setAntennaTier(int tier) {
        antennaTier = tier;
        setChanged();
        // The top face follows the antenna.
        BlockState state = getBlockState();
        if (level != null && !level.isClientSide && state.hasProperty(ControllerBlock.ANTENNA) && state.getValue(ControllerBlock.ANTENNA) != tier) {
            level.setBlock(worldPosition, state.setValue(ControllerBlock.ANTENNA, Math.max(0, Math.min(3, tier))), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    /** Its network's owner when last saved: a network deleted from under it goes back to them (see Topology.reclaimController). */
    @javax.annotation.Nullable
    private java.util.UUID lastOwner;
    private String lastOwnerName = "";

    @javax.annotation.Nullable
    public java.util.UUID lastOwner() {
        return lastOwner;
    }

    public String lastOwnerName() {
        return lastOwnerName;
    }

    /** Remembers whose its network is (or nobody's), as that network is deleted. */
    public void rememberOwner(@javax.annotation.Nullable java.util.UUID owner, String name) {
        lastOwner = owner;
        lastOwnerName = owner == null ? "" : name;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        antennaTier = tag.getByte("antenna");
        energy = tag.getLong("energy");
        lastOwner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        lastOwnerName = tag.getString("owner_name");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("antenna", (byte) antennaTier);
        tag.putLong("energy", energy);
        if (level instanceof net.minecraft.server.level.ServerLevel server && networkId() != null) {
            var network = io.github.leadassimilator.reticulum.network.NetworkManager.network(server.getServer(), networkId());
            if (network != null && network.owner() != null) {
                lastOwner = network.owner();
                lastOwnerName = network.ownerName();
            }
        }
        if (lastOwner != null) {
            tag.putUUID("owner", lastOwner);
            tag.putString("owner_name", lastOwnerName);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        // Only the field: the block state already has the tier (set as it was placed).
        antennaTier = input.getOrDefault(ModDataComponents.ANTENNA_TIER.get(), 0);
        // Within what it holds now (the capacity may have been lowered since it was charged).
        energy = Math.min(capacity(), Math.max(0, input.getOrDefault(ModDataComponents.STORED_ENERGY.get(), 0L)));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        // The network it remembers, unless that is gone (an empty one is deleted as the block comes out, before the
        // drop is made): then it drops as a fresh controller.
        // An explosion or a machine makes the drop before the block comes out: a network about to go with it (empty,
        // this its controller) counts as gone already.
        boolean gone = false;
        if (level instanceof net.minecraft.server.level.ServerLevel server && networkId() != null) {
            var network = io.github.leadassimilator.reticulum.network.NetworkManager.network(server.getServer(), networkId());
            gone = network == null || io.github.leadassimilator.reticulum.network.NetworkManager.isEmpty(network)
                    && io.github.leadassimilator.reticulum.network.Devices.global(level, worldPosition).equals(network.controller());
        }
        if (networkId() != null && !gone) {
            components.set(ModDataComponents.NETWORK_ID.get(), networkId());
        }
        if (antennaTier > 0) {
            components.set(ModDataComponents.ANTENNA_TIER.get(), antennaTier);
        }
        if (energy > 0) {
            components.set(ModDataComponents.STORED_ENERGY.get(), energy);
        }
        if (level instanceof net.minecraft.server.level.ServerLevel server && networkId() != null) {
            var network = io.github.leadassimilator.reticulum.network.NetworkManager.network(server.getServer(), networkId());
            if (network != null && !gone) {
                components.set(ModDataComponents.NETWORK_NAME.get(), network.name());
            }
        }
    }
}
