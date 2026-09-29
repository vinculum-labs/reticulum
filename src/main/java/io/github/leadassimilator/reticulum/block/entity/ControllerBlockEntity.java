package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.world.level.block.state.BlockState;

public class ControllerBlockEntity extends NetworkBlockEntity {
    /** Network remembered by the controller item this block was placed from; consumed by topology. */
    @Nullable
    private UUID itemNetworkId;
    /** Installed antenna upgrade, 0 (none) to 3. */
    private int antennaTier;
    /** FE held to run the network (only used when the server's power preset charges for it). */
    private long energy;

    /** Takes FE from anything that pushes into the controller; gives none back. */
    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
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

    @Nullable
    public UUID takeItemNetworkId() {
        UUID id = itemNetworkId;
        itemNetworkId = null;
        return id;
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        antennaTier = tag.getByte("antenna");
        energy = tag.getLong("energy");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("antenna", (byte) antennaTier);
        tag.putLong("energy", energy);
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
