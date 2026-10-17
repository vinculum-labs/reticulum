package io.github.leadassimilator.reticulum.block.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.engine.Power;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public class ControllerBlockEntity extends NetworkBlockEntity {
    /** Installed antenna upgrade, 0 (none) to 3. */
    private int antennaTier;
    /** FE held to run the network (only used when the server's power preset charges for it). */
    private long energy;

    /** Takes FE from anything that pushes into the controller (in transactions, see EnergyHandler); gives none back. */
    private final EnergyHandler energyHandler = new EnergyHandler() {
        private final SnapshotJournal<Long> journal = new SnapshotJournal<>() {
            @Override
            protected Long createSnapshot() {
                return energy;
            }

            @Override
            protected void revertToSnapshot(Long snapshot) {
                energy = snapshot;
            }

            @Override
            protected void onRootCommit(Long original) {
                if (energy != original) {
                    setChanged();
                }
            }
        };

        @Override
        public long getAmountAsLong() {
            return energy;
        }

        @Override
        public long getCapacityAsLong() {
            return capacity();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            int accepted = room(amount);
            if (accepted > 0) {
                journal.updateSnapshots(transaction);
                energy += accepted;
            }
            return accepted;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    };

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONTROLLER.get(), pos, state);
    }

    public EnergyHandler energyHandler() {
        return energyHandler;
    }

    /** Puts FE in directly (the network's own energy flows into its controller); returns how much it took. */
    public int charge(int amount) {
        int accepted = room(amount);
        if (accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    /**
     * How much of {@code amount} FE fits. With power free (no preset charging for it) it takes nothing, so energy isn't
     * soaked up for no use (and a broken controller stacks with fresh ones).
     */
    private int room(int amount) {
        if (!Power.current().enabled()) {
            return 0;
        }
        return (int) Math.min(Math.max(0, capacity() - energy), Math.max(0, amount));
    }

    public long energy() {
        return energy;
    }

    public static long capacity() {
        return ReticulumConfig.CONTROLLER_CAPACITY.getAsInt();
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
        if (level != null && !level.isClientSide() && state.hasProperty(ControllerBlock.ANTENNA) && state.getValue(ControllerBlock.ANTENNA) != tier) {
            level.setBlock(worldPosition, state.setValue(ControllerBlock.ANTENNA, Math.max(0, Math.min(3, tier))), Block.UPDATE_ALL);
        }
    }

    /** Its network's owner when last saved: a network deleted from under it goes back to them (see Topology.reclaimController). */
    @Nullable
    private UUID lastOwner;
    private String lastOwnerName = "";

    @Nullable
    public UUID lastOwner() {
        return lastOwner;
    }

    public String lastOwnerName() {
        return lastOwnerName;
    }

    /** Remembers whose its network is (or nobody's), as that network is deleted. */
    public void rememberOwner(@Nullable UUID owner, String name) {
        lastOwner = owner;
        lastOwnerName = owner == null ? "" : name;
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput tag) {
        super.loadAdditional(tag);
        antennaTier = tag.getByteOr("antenna", (byte) 0);
        energy = tag.getLongOr("energy", 0L);
        lastOwner = tag.read("owner", UUIDUtil.CODEC).orElse(null);
        lastOwnerName = tag.getStringOr("owner_name", "");
    }

    @Override
    protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putByte("antenna", (byte) antennaTier);
        tag.putLong("energy", energy);
        if (level instanceof ServerLevel server && networkId() != null) {
            var network = NetworkManager.network(server.getServer(), networkId());
            if (network != null && network.owner() != null) {
                lastOwner = network.owner();
                lastOwnerName = network.ownerName();
            }
        }
        if (lastOwner != null) {
            tag.store("owner", UUIDUtil.CODEC, lastOwner);
            tag.putString("owner_name", lastOwnerName);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter input) {
        super.applyImplicitComponents(input);
        // Only the field: the block state already has the tier (set as it was placed).
        antennaTier = input.getOrDefault(ModDataComponents.ANTENNA_TIER.get(), 0);
        // Within what it holds now (the capacity may have been lowered since it was charged).
        energy = Math.min(capacity(), Math.max(0, input.getOrDefault(ModDataComponents.STORED_ENERGY.get(), 0L)));
        // Read so the block doesn't keep it: the name it drops with is the network's as it is then, or none.
        input.get(ModDataComponents.NETWORK_NAME.get());
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        // The network it remembers, unless that is gone (an empty one is deleted as the block comes out, before the
        // drop is made): then it drops as a fresh controller.
        // An explosion or a machine makes the drop before the block comes out: a network about to go with it (empty,
        // this its controller) counts as gone already.
        boolean gone = false;
        if (level instanceof ServerLevel server && networkId() != null) {
            var network = NetworkManager.network(server.getServer(), networkId());
            gone = network == null || NetworkManager.isEmpty(network)
                    && Devices.global(level, worldPosition).equals(network.controller());
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
        if (level instanceof ServerLevel server && networkId() != null) {
            var network = NetworkManager.network(server.getServer(), networkId());
            if (network != null && !gone) {
                components.set(ModDataComponents.NETWORK_NAME.get(), network.name());
            }
        }
    }
}
