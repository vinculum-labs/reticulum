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
 * Shows a state with a look of its own (color, beam, glow, sound; see {@link BeaconLook}). In Network mode the states
 * are the worst alarm going off on the network of the connector it is attached to: a warning or critical. In Redstone
 * mode they are the signal it gets, 0 to 15, each level with a look of its own or the same as the level below.
 */
public class StatusBeaconBlockEntity extends BlockEntity {
    public enum Mode implements LowercaseName {
        /** The worst alarm going off on the network of the connector it is attached to. */
        NETWORK,
        /** The redstone signal it gets, 1 to 15. */
        REDSTONE
    }

    /** Loaded beacons on the client, for the glow and the sound (drawn and played each frame and tick). */
    public static final Set<StatusBeaconBlockEntity> CLIENT_LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Mode mode = Mode.NETWORK;
    private BeaconLook warning = BeaconLook.WARNING;
    private BeaconLook critical = BeaconLook.CRITICAL;
    /** Redstone mode: the look of each level (0 to 15), or null for the same as the level below (level 0: nothing shown). */
    private final BeaconLook[] levels = new BeaconLook[16];
    /** Client: how far the beam reaches up and down from the block, worked out now and then (not every frame). */
    private int beamUp;
    private int beamDown;

    public StatusBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATUS_BEACON.get(), pos, state);
        levels[1] = BeaconLook.WARNING;
        levels[8] = BeaconLook.CRITICAL;
    }

    public Mode mode() {
        return mode;
    }

    /** The look of a level in Redstone mode: its own, else the nearest level below with one (null: nothing shown). */
    @Nullable
    public BeaconLook levelLook(int level) {
        for (int i = Math.min(15, level); i >= 0; i--) {
            if (levels[i] != null) {
                return levels[i];
            }
        }
        return null;
    }

    /** The look shown now (from the block state), or null when nothing is. */
    @Nullable
    public BeaconLook look() {
        int shown = getBlockState().getValue(StatusBeaconBlock.SHOWN);
        return shown == StatusBeaconBlock.WARNING ? warning : shown == StatusBeaconBlock.CRITICAL ? critical
                : shown == StatusBeaconBlock.LEVEL_ZERO ? levelLook(0) : shown >= 1 && shown <= 15 ? levelLook(shown) : null;
    }

    public int beamUp() {
        return beamUp;
    }

    public int beamDown() {
        return beamDown;
    }

    // ---------------------------------------------------------------- settings

    /** Its mode and every look, as the screen edits them. */
    public CompoundTag settings() {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", mode.getSerializedName());
        tag.put("warning", warning.save());
        tag.put("critical", critical.save());
        CompoundTag own = new CompoundTag();
        for (int i = 0; i <= 15; i++) {
            if (levels[i] != null) {
                own.put(Integer.toString(i), levels[i].save());
            }
        }
        tag.put("levels", own);
        return tag;
    }

    /** Takes settings (from the screen or the save), kept within the server's limits. */
    public void readSettings(CompoundTag tag) {
        mode = "redstone".equals(tag.getString("mode")) ? Mode.REDSTONE : Mode.NETWORK;
        warning = tag.contains("warning") ? BeaconLook.load(tag.getCompound("warning"), BeaconLook.WARNING) : BeaconLook.WARNING;
        critical = tag.contains("critical") ? BeaconLook.load(tag.getCompound("critical"), BeaconLook.CRITICAL) : BeaconLook.CRITICAL;
        if (tag.contains("levels")) {
            CompoundTag own = tag.getCompound("levels");
            for (int i = 0; i <= 15; i++) {
                String key = Integer.toString(i);
                levels[i] = own.contains(key) ? BeaconLook.load(own.getCompound(key), i >= 8 ? BeaconLook.CRITICAL : BeaconLook.WARNING) : null;
            }
        }
    }

    /** Gives the look showing now another color (the level it comes from, in Redstone mode); false when nothing shows. */
    public boolean recolorShown(int color) {
        int shown = getBlockState().getValue(StatusBeaconBlock.SHOWN);
        if (shown == StatusBeaconBlock.WARNING) {
            warning = recolor(warning, color);
        } else if (shown == StatusBeaconBlock.CRITICAL) {
            critical = recolor(critical, color);
        } else if (shown >= 1 && shown <= 15 || shown == StatusBeaconBlock.LEVEL_ZERO) {
            int from = shown == StatusBeaconBlock.LEVEL_ZERO ? 0 : shown;
            while (from > 0 && levels[from] == null) {
                from--;
            }
            if (levels[from] == null) {
                return false;
            }
            levels[from] = recolor(levels[from], color);
        } else {
            return false;
        }
        applySettings(settings());
        return true;
    }

    private static BeaconLook recolor(BeaconLook look, int color) {
        return new BeaconLook(color, look.flags(), look.sound(), look.pitch(), look.interval(), look.range());
    }

    /** New settings from the screen: saved, sent to clients, and shown at once. */
    public void applySettings(CompoundTag tag) {
        readSettings(tag);
        setChanged();
        if (level instanceof ServerLevel server) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            update(server, worldPosition, getBlockState(), this);
        }
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

    /** What it should show now, as a {@link StatusBeaconBlock#SHOWN} value. */
    public int wanted(ServerLevel level) {
        if (mode == Mode.REDSTONE) {
            int signal = level.getBestNeighborSignal(worldPosition);
            return levelLook(signal) == null ? 0 : signal == 0 ? StatusBeaconBlock.LEVEL_ZERO : signal;
        }
        Watch watch = watch(level);
        AlarmSeverity worst = watch.network() == null || !watch.reachable() ? null : Alarms.worst(Alarms.cached(level.getServer(), watch.network()));
        return worst == null ? 0 : worst == AlarmSeverity.CRITICAL ? StatusBeaconBlock.CRITICAL : StatusBeaconBlock.WARNING;
    }

    // ---------------------------------------------------------------- ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        // Redstone is caught as it changes (the block hears its neighbors); this is for the network, and a fallback.
        if (level instanceof ServerLevel server && (level.getGameTime() + pos.asLong()) % 20 == 0) {
            update(server, pos, state, beacon);
        }
    }

    public static void update(ServerLevel level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        int shown = beacon.wanted(level);
        if (state.getValue(StatusBeaconBlock.SHOWN) != shown) {
            level.setBlock(pos, state.setValue(StatusBeaconBlock.SHOWN, shown), Block.UPDATE_ALL);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, StatusBeaconBlockEntity beacon) {
        if ((level.getGameTime() + pos.asLong()) % 10 != 0) {
            return;
        }
        BeaconLook look = beacon.look();
        boolean through = look != null && look.has(BeaconLook.BEAM_THROUGH);
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
        tag.put("settings", settings());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("settings")) {
            readSettings(tag.getCompound("settings"));
        }
        // New looks on the client: the lens is tinted by the look, so its chunk is drawn again.
        if (level != null && level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("settings", settings());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
