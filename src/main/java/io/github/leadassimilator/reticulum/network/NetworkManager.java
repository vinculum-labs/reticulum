package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** Server-side operations on networks that keep connectors and device records consistent. */
public final class NetworkManager {
    private NetworkManager() {
    }

    public static ReticulumSavedData data(MinecraftServer server) {
        return ReticulumSavedData.get(server);
    }

    @Nullable
    public static Network network(MinecraftServer server, @Nullable UUID id) {
        return data(server).network(id);
    }

    /** Marks a network as changed: it will be saved, and web UI subscribers get a fresh snapshot. */
    public static void changed(MinecraftServer server, Network network) {
        network.bump();
        data(server).setDirty();
    }

    public static Network create(MinecraftServer server, @Nullable Player owner) {
        ReticulumSavedData data = data(server);
        String name = owner != null
                ? owner.getGameProfile().getName() + "'s network " + (count(data, owner.getUUID()) + 1)
                : "Network " + (data.networks().size() + 1);
        // A network placed by a player starts private; one placed by automation has no owner and starts public.
        Network network = new Network(UUID.randomUUID(), name,
                owner == null ? null : owner.getUUID(),
                owner == null ? "" : owner.getGameProfile().getName(),
                owner == null ? AccessMode.PUBLIC : AccessMode.PRIVATE);
        data.add(network);
        return network;
    }

    private static int count(ReticulumSavedData data, UUID owner) {
        int n = 0;
        for (Network network : data.networks()) {
            if (owner.equals(network.owner())) {
                n++;
            }
        }
        return n;
    }

    /** Adds the connector if needed and replaces the set of devices it reaches. */
    public static void updateConnector(ServerLevel level, Network network, UUID connectorId, GlobalPos connectorPos, List<GlobalPos> devices) {
        ConnectorRecord record = network.connectorMap().computeIfAbsent(connectorId, id -> new ConnectorRecord(id, connectorPos));
        record.setPos(connectorPos);
        List<GlobalPos> old = new ArrayList<>(record.devices());
        record.devices().clear();
        record.devices().addAll(devices);
        for (GlobalPos device : devices) {
            attachDevice(level.getServer(), network, device);
        }
        for (GlobalPos device : old) {
            if (!devices.contains(device)) {
                releaseDevice(level.getServer(), network, device);
            }
        }
        changed(level.getServer(), network);
    }

    /** Removes a connector from its network; devices nothing else reaches follow the record lifecycle. */
    public static void removeConnector(MinecraftServer server, Network network, UUID connectorId) {
        ConnectorRecord record = network.connectorMap().remove(connectorId);
        if (record != null) {
            for (GlobalPos device : record.devices()) {
                releaseDevice(server, network, device);
            }
            changed(server, network);
        }
    }

    private static void attachDevice(MinecraftServer server, Network network, GlobalPos pos) {
        ServerLevel level = server.getLevel(pos.dimension());
        DeviceRecord record = network.deviceMap().get(pos);
        if (record == null) {
            if (level == null) {
                return;
            }
            network.deviceMap().put(pos, new DeviceRecord(pos, Devices.blockId(level, pos.pos())));
            return;
        }
        record.setState(DeviceRecord.State.ACTIVE);
        if (level != null && level.isLoaded(pos.pos())) {
            // Phase 1 keeps names when the block type changes; the web UI will ask the user instead.
            record.setBlockId(Devices.blockId(level, pos.pos()));
        }
    }

    private static void releaseDevice(MinecraftServer server, Network network, GlobalPos pos) {
        if (network.isReached(pos)) {
            return;
        }
        DeviceRecord record = network.deviceMap().get(pos);
        if (record == null) {
            return;
        }
        if (!record.hasUserData()) {
            network.deviceMap().remove(pos);
            return;
        }
        ServerLevel level = server.getLevel(pos.dimension());
        boolean present = level != null && Devices.isDevice(level, pos.pos())
                && Objects.equals(Devices.blockId(level, pos.pos()), record.blockId());
        record.setState(present ? DeviceRecord.State.ORPHANED : DeviceRecord.State.MISSING);
    }

    /** Deletes an orphaned or missing device record. Active records cannot be deleted. */
    public static boolean deleteOrphan(MinecraftServer server, Network network, GlobalPos pos) {
        DeviceRecord record = network.deviceMap().get(pos);
        if (record == null || record.state() == DeviceRecord.State.ACTIVE) {
            return false;
        }
        network.deviceMap().remove(pos);
        changed(server, network);
        return true;
    }

    public static int orphanCount(Network network) {
        int n = 0;
        for (DeviceRecord record : network.devices()) {
            if (record.state() != DeviceRecord.State.ACTIVE) {
                n++;
            }
        }
        return n;
    }
}
