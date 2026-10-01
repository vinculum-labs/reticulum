package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Role;
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

    /** True when the network holds nothing a player made: no sheets, devices, connectors, filters or overflow. */
    /**
     * Moves an orphaned or missing device's names, face names, identifies and every flow reference onto another
     * device (a machine moved or replaced), then forgets the old record. Returns false when either isn't suitable.
     */
    public static boolean relinkDevice(MinecraftServer server, Network network, GlobalPos from, GlobalPos to) {
        DeviceRecord old = network.device(from);
        DeviceRecord target = network.device(to);
        if (old == null || target == null || from.equals(to) || old.state() == DeviceRecord.State.ACTIVE) {
            return false;
        }
        if (old.name() != null) {
            target.setName(old.name());
        }
        old.faceNames().forEach(target::setFaceName);
        for (io.github.leadassimilator.reticulum.graph.Sheet sheet : network.sheets()) {
            for (io.github.leadassimilator.reticulum.graph.Node node : List.copyOf(sheet.nodes())) {
                io.github.leadassimilator.reticulum.graph.NodeSettings moved = switch (node.settings()) {
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings d when d.device().equals(from) ->
                            new io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings(to, d.inputs(), d.outputs());
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.LevelSettings l when l.device().equals(from) ->
                            new io.github.leadassimilator.reticulum.graph.NodeSettings.LevelSettings(l.resource(), to, l.faces(), l.filter());
                    default -> null;
                };
                if (moved != null) {
                    sheet.replace(new io.github.leadassimilator.reticulum.graph.Node(node.id(), moved, node.label(), node.enabled(), node.x(), node.y(),
                            node.redstone()));
                }
            }
        }
        network.identified().replaceAll(identify -> identify.device().filter(from::equals).isPresent()
                ? new Identify(identify.kind(), identify.connector(), java.util.Optional.of(to), identify.face(), identify.color(), identify.since())
                : identify);
        network.deviceMap().remove(from);
        changed(server, network);
        return true;
    }

    public static boolean isEmpty(Network network) {
        return network.sheets().isEmpty() && network.devices().isEmpty() && network.connectors().isEmpty()
                && network.filters().isEmpty() && network.overflow().isEmpty();
    }

    /**
     * Deletes a network. Blocks still carrying its id behave as if they had no network; a controller of a deleted
     * network gets a new one the next time it is opened.
     */
    public static void delete(MinecraftServer server, Network network) {
        network.bump();
        data(server).remove(network.id());
        io.github.leadassimilator.reticulum.engine.NetworkTicker.forget(server, network);
    }

    /**
     * Merges {@code from} into {@code into}: sheets, filters, members, connectors, overflow, switches, signal memory,
     * identifies, and device and face names (where {@code into} has none). {@code from} is removed, and blocks still
     * carrying its id follow it.
     *
     * <p>{@code from}'s owner and members join {@code into} with their roles capped at {@code cap} (the role of the
     * player doing the merge) and never above admin, so a merge can't hand anyone more than its maker has. Members
     * {@code into} already has keep their role.
     */
    public static void absorb(MinecraftServer server, Network into, Network from, Role cap) {
        if (into == from) {
            return;
        }
        Role most = cap.atLeast(Role.ADMIN) ? Role.ADMIN : cap;
        java.util.Set<String> folders = new java.util.HashSet<>();
        into.sheets().forEach(sheet -> folders.add(sheet.folder()));
        into.sheets().addAll(from.sheets());
        from.filters().forEach(into.filters()::putIfAbsent);
        List<Member> joining = new ArrayList<>(from.members().values());
        if (from.owner() != null) {
            joining.add(new Member(from.owner(), from.ownerName(), Role.OWNER));
        }
        for (Member member : joining) {
            if (!member.id().equals(into.owner())) {
                Role role = member.role().atLeast(most) ? most : member.role();
                into.members().putIfAbsent(member.id(), new Member(member.id(), member.name(), role));
            }
        }
        from.disabledFolders().stream().filter(folder -> !folders.contains(folder)).forEach(into.disabledFolders()::add);
        from.switches().folderRedstone().forEach((folder, redstone) -> {
            if (!folders.contains(folder)) {
                into.switches().folderRedstone().putIfAbsent(folder, redstone);
            }
        });
        from.signalMemory().forEach(into.signalMemory()::putIfAbsent);
        for (io.github.leadassimilator.reticulum.network.Identify identify : from.identified()) {
            if (into.identified().size() < Limits.IDENTIFIES && into.identified().stream().noneMatch(identify::sameTarget)) {
                into.identified().add(identify);
            }
        }
        for (ConnectorRecord connector : from.connectors()) {
            into.connectorMap().putIfAbsent(connector.id(), connector);
        }
        for (DeviceRecord device : from.devices()) {
            DeviceRecord existing = into.device(device.pos());
            if (existing == null) {
                into.deviceMap().put(device.pos(), device);
                continue;
            }
            if (existing.name() == null && device.name() != null) {
                existing.setName(device.name());
            }
            device.faceNames().forEach((face, name) -> {
                if (existing.faceName(face) == null) {
                    existing.setFaceName(face, name);
                }
            });
        }
        for (DeviceRecord device : into.devices()) {
            if (into.isReached(device.pos())) {
                device.setState(DeviceRecord.State.ACTIVE);
            }
        }
        into.overflowItems().addAll(from.overflowItems());
        into.overflowFluids().addAll(from.overflowFluids());
        from.overflow().chemicals().forEach((id, amount) -> into.overflow().chemicals().merge(id, amount, Long::sum));
        delete(server, from);
        data(server).alias(from.id(), into.id());
        changed(server, into);
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
