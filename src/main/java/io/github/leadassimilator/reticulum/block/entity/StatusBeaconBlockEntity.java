package io.github.leadassimilator.reticulum.block.entity;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import io.github.leadassimilator.reticulum.engine.Alarms;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Watches one network's alarms and shows the worst going off (in its block state, so it lights up and changes
 * color), with a beam, a glow and a sound as set for each severity.
 */
public class StatusBeaconBlockEntity extends BlockEntity {
    /** Per severity, what the beacon does while one of that severity is going off. */
    public static final int BEAM = 1;
    /** The beam goes on through solid blocks (otherwise it stops at the first). */
    public static final int BEAM_THROUGH = 2;
    public static final int GLOW = 4;
    /** The glow shows through walls and floors. */
    public static final int GLOW_THROUGH = 8;
    public static final int SOUND = 16;
    public static final int ALL = BEAM | BEAM_THROUGH | GLOW | GLOW_THROUGH | SOUND;

    /** Loaded beacons on the client, for the glow and the sound (drawn and played each frame and tick). */
    public static final Set<StatusBeaconBlockEntity> CLIENT_LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Nullable
    private UUID networkId;
    private int warning = BEAM | GLOW;
    private int critical = ALL;
    /** Client: how far the beam reaches up and down from the block, worked out now and then (not every frame). */
    private int beamUp;
    private int beamDown;

    public StatusBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATUS_BEACON.get(), pos, state);
    }

    @Nullable
    public UUID networkId() {
        return networkId;
    }

    public void setNetworkId(@Nullable UUID networkId) {
        this.networkId = networkId;
        changed();
    }

    /** What the beacon does for a severity: a mask of {@link #BEAM} and the rest. */
    public int style(AlarmSeverity severity) {
        return severity == AlarmSeverity.CRITICAL ? critical : warning;
    }

    public void setStyles(int warning, int critical) {
        this.warning = warning & ALL;
        this.critical = critical & ALL;
        changed();
    }

    public int warningStyle() {
        return warning;
    }

    public int criticalStyle() {
        return critical;
    }

    /** The severity shown, from the block state (null: nothing going off). */
    @Nullable
    public AlarmSeverity shown() {
        return getBlockState().getValue(StatusBeaconBlock.ALARM).severity();
    }

    public int beamUp() {
        return beamUp;
    }

    public int beamDown() {
        return beamDown;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ---------------------------------------------------------------- ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        if (!(level instanceof ServerLevel server) || (level.getGameTime() + pos.asLong()) % 20 != 0) {
            return;
        }
        Network network = beacon.networkId == null ? null : NetworkManager.network(server.getServer(), beacon.networkId);
        AlarmSeverity worst = network == null ? null : Alarms.worst(Alarms.cached(server.getServer(), network));
        StatusBeaconBlock.Shown shown = StatusBeaconBlock.Shown.of(worst);
        if (state.getValue(StatusBeaconBlock.ALARM) != shown) {
            level.setBlock(pos, state.setValue(StatusBeaconBlock.ALARM, shown), Block.UPDATE_ALL);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        if ((level.getGameTime() + pos.asLong()) % 10 != 0) {
            return;
        }
        AlarmSeverity shown = beacon.shown();
        int style = shown == null ? 0 : beacon.style(shown);
        boolean through = (style & BEAM_THROUGH) != 0;
        beacon.beamUp = reach(level, pos, 1, through);
        beacon.beamDown = reach(level, pos, -1, through);
    }

    /** How many blocks the beam runs up ({@code step} 1) or down (-1): to the world's edge, or to the first solid block. */
    private static int reach(Level level, BlockPos pos, int step, boolean through) {
        int edge = step > 0 ? level.getMaxBuildHeight() - pos.getY() - 1 : pos.getY() - level.getMinBuildHeight();
        if (through) {
            return Math.max(0, edge);
        }
        BlockPos.MutableBlockPos at = pos.mutable();
        for (int i = 1; i <= edge; i++) {
            at.setY(pos.getY() + step * i);
            if (level.getBlockState(at).canOcclude()) {
                return i - 1;
            }
        }
        return Math.max(0, edge);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            CLIENT_LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LOADED.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LOADED.remove(this);
    }

    // ---------------------------------------------------------------- saving

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (networkId != null) {
            tag.putUUID("network", networkId);
        }
        tag.putInt("warning", warning);
        tag.putInt("critical", critical);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        networkId = tag.hasUUID("network") ? tag.getUUID("network") : null;
        if (tag.contains("warning")) {
            warning = tag.getInt("warning") & ALL;
        }
        if (tag.contains("critical")) {
            critical = tag.getInt("critical") & ALL;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("warning", warning);
        tag.putInt("critical", critical);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** A beacon item from a bound stack (or one broken off a network) watches that network. */
    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        UUID bound = input.get(ModDataComponents.NETWORK_ID.get());
        if (bound != null) {
            networkId = bound;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (networkId != null) {
            components.set(ModDataComponents.NETWORK_ID.get(), networkId);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("network");
    }
}
