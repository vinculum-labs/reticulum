package io.github.leadassimilator.reticulum.block.entity;

import java.util.Set;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import io.github.leadassimilator.reticulum.engine.Alarms;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
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
 * Shows an alarm as gray, yellow or red (in its block state, so it lights up and changes color), with a beam, a glow
 * and a sound as set for each severity. In Network mode it shows the worst going off on the network of the connector
 * it is attached to; in Redstone mode, the signal it gets (1 to 7 a warning, 8 to 15 critical).
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

    public enum Mode implements LowercaseName {
        /** The worst alarm going off on the network of the connector it is attached to. */
        NETWORK,
        /** The redstone signal it gets: 1 to 7 a warning, 8 to 15 critical. */
        REDSTONE
    }

    /** Loaded beacons on the client, for the glow and the sound (drawn and played each frame and tick). */
    public static final Set<StatusBeaconBlockEntity> CLIENT_LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Mode mode = Mode.NETWORK;
    private int warning = BEAM | GLOW;
    private int critical = ALL;
    /** Client: how far the beam reaches up and down from the block, worked out now and then (not every frame). */
    private int beamUp;
    private int beamDown;

    public StatusBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATUS_BEACON.get(), pos, state);
    }

    public Mode mode() {
        return mode;
    }

    /** What the beacon does for a severity: a mask of {@link #BEAM} and the rest. */
    public int style(AlarmSeverity severity) {
        return severity == AlarmSeverity.CRITICAL ? critical : warning;
    }

    public int warningStyle() {
        return warning;
    }

    public int criticalStyle() {
        return critical;
    }

    public void setSettings(Mode mode, int warning, int critical) {
        boolean modeChanged = this.mode != mode;
        this.mode = mode;
        this.warning = warning & ALL;
        this.critical = critical & ALL;
        setChanged();
        if (level instanceof ServerLevel server) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            if (modeChanged) {
                update(server, worldPosition, getBlockState(), this);
            }
        }
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

    // ---------------------------------------------------------------- what it watches

    /**
     * The network a beacon is a device of (through the connector it is attached to), and whether that connector reaches
     * the controller. With several, one it reaches is preferred.
     */
    public record Watch(@Nullable Network network, boolean reachable) {
    }

    public Watch watch(ServerLevel level) {
        GlobalPos at = Devices.global(level, worldPosition);
        Network best = null;
        boolean reached = false;
        for (Network network : NetworkManager.networksWith(level.getServer(), at)) {
            DeviceRecord record = network.device(at);
            if (record == null || record.state() != DeviceRecord.State.ACTIVE) {
                continue;
            }
            boolean reachable = NetworkTicker.isRunning(level.getServer(), network) && !NetworkTicker.runtime(network.id()).outOfRange(at);
            if (best == null || reachable && !reached) {
                best = network;
                reached = reachable;
            }
        }
        return new Watch(best, reached);
    }

    /** What it should show now: the network's worst alarm (if its connector reaches it), or what the signal says. */
    @Nullable
    public AlarmSeverity wanted(ServerLevel level) {
        if (mode == Mode.REDSTONE) {
            int signal = level.getBestNeighborSignal(worldPosition);
            return signal >= 8 ? AlarmSeverity.CRITICAL : signal > 0 ? AlarmSeverity.WARNING : null;
        }
        Watch watch = watch(level);
        return watch.network() == null || !watch.reachable() ? null : Alarms.worst(Alarms.cached(level.getServer(), watch.network()));
    }

    // ---------------------------------------------------------------- ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        // Redstone is caught as it changes (the block hears its neighbors); this is for the network, and a fallback.
        if (level instanceof ServerLevel server && (level.getGameTime() + pos.asLong()) % 20 == 0) {
            update(server, pos, state, beacon);
        }
    }

    public static void update(ServerLevel level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        StatusBeaconBlock.Shown shown = StatusBeaconBlock.Shown.of(beacon.wanted(level));
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
        tag.putString("mode", mode.getSerializedName());
        tag.putInt("warning", warning);
        tag.putInt("critical", critical);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = "redstone".equals(tag.getString("mode")) ? Mode.REDSTONE : Mode.NETWORK;
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
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
