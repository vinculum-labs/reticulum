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

    /**
     * The overflow buffer changed: saved, but not a change to the network (its revision stays), which would have the
     * engine walk the cables again and every open editor get a whole new snapshot, as often as every tick while it drains.
     */
    public static void overflowChanged(MinecraftServer server, Network network) {
        network.touchOverflow();
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

    /** Every network that has the device (a block can be a device of several networks, through different connectors). */
    public static List<Network> networksWith(MinecraftServer server, GlobalPos device) {
        List<Network> found = new ArrayList<>();
        for (Network network : data(server).networks()) {
            if (network.device(device) != null) {
                found.add(network);
            }
        }
        return found;
    }

    /**
     * The network's controller, if its chunk is loaded and it still belongs to this network (following merges);
     * never loads the chunk.
     */
    @Nullable
    public static io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity controller(MinecraftServer server, Network network) {
        GlobalPos at = network.controller();
        ServerLevel level = at == null ? null : server.getLevel(at.dimension());
        if (level == null || !level.isLoaded(at.pos())
                || !(level.getBlockEntity(at.pos()) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity controller)) {
            return null;
        }
        return network(server, controller.networkId()) == network ? controller : null;
    }

    /**
     * Whether any sheet uses the device (a node on it, a node reading it, or a blueprint instance's slot it fills), or a
     * chart does: such a record is kept when the block goes, so its flows and charts can be moved onto the block that
     * replaces it.
     */
    static boolean inUse(Network network, GlobalPos pos) {
        for (io.github.leadassimilator.reticulum.graph.Sheet sheet : network.sheets()) {
            for (io.github.leadassimilator.reticulum.graph.Node node : sheet.nodes()) {
                if (node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.InstanceSettings instance
                        && instance.bindings().values().stream().anyMatch(b -> pos.equals(b.device()))) {
                    return true;
                }
                GlobalPos used = switch (node.settings()) {
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings s -> s.device();
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.LevelSettings s -> s.device();
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneInSettings s -> s.device();
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneOutSettings s -> s.device();
                    default -> null;
                };
                if (pos.equals(used)) {
                    return true;
                }
            }
        }
        return network.charts().stream().anyMatch(c -> c.device().filter(pos::equals).isPresent());
    }

    /**
     * A device whose connector moved to another network, its names going along: the record left behind is dropped,
     * unless something still reaches it here or a sheet here uses it (then it shows as orphaned, so that is seen).
     */
    public static void forgetMovedDevice(MinecraftServer server, Network network, GlobalPos pos) {
        if (!network.isReached(pos) && !inUse(network, pos) && network.deviceMap().remove(pos) != null) {
            changed(server, network);
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
        if (!record.hasUserData() && !inUse(network, pos)) {
            network.deviceMap().remove(pos);
            return;
        }
        ServerLevel level = server.getLevel(pos.dimension());
        boolean present = level != null && (Devices.isDevice(level, pos.pos())
                || level.getBlockEntity(pos.pos()) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity)
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
        network.charts().removeIf(c -> c.device().filter(pos::equals).isPresent());
        changed(server, network);
        return true;
    }

    /**
     * Moves an orphaned or missing device's names, face names, identifies, charts and every flow reference onto another
     * device (a machine moved or replaced), then forgets the old record. Returns false when either isn't suitable.
     */
    public static boolean relinkDevice(MinecraftServer server, Network network, GlobalPos from, GlobalPos to) {
        DeviceRecord old = network.device(from);
        DeviceRecord target = network.device(to);
        // Not onto a device something already uses (a node, a slot, a chart): it would end up with two.
        if (old == null || target == null || from.equals(to) || old.state() == DeviceRecord.State.ACTIVE || inUse(network, to)) {
            return false;
        }
        if (old.name() != null) {
            target.setName(old.name());
        }
        old.faceNames().forEach(target::setFaceName);
        for (io.github.leadassimilator.reticulum.graph.Sheet sheet : network.sheets()) {
            for (io.github.leadassimilator.reticulum.graph.Node node : List.copyOf(sheet.nodes())) {
                io.github.leadassimilator.reticulum.graph.NodeSettings moved = switch (node.settings()) {
                    // A blueprint slot keeps its kind of block (its device is only where its layout measures from).
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings d when d.device().equals(from) ->
                            new io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings(to, d.inputs(), d.outputs(), d.block());
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.LevelSettings l when l.device().equals(from) ->
                            l.reading(to, l.faces());
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneInSettings r when r.device().equals(from) ->
                            new io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneInSettings(to, r.faces(), r.comparator());
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneOutSettings r when r.device().equals(from) ->
                            new io.github.leadassimilator.reticulum.graph.NodeSettings.RedstoneOutSettings(to, r.faces(), r.whileOff());
                    // An instance whose slot the device filled: the new block fills it, turned the same.
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.InstanceSettings c when c.bindings().values().stream().anyMatch(b -> from.equals(b.device())) -> {
                        java.util.Map<UUID, io.github.leadassimilator.reticulum.graph.NodeSettings.Binding> bindings = new java.util.LinkedHashMap<>();
                        c.bindings().forEach((slot, b) -> bindings.put(slot, from.equals(b.device()) ? new io.github.leadassimilator.reticulum.graph.NodeSettings.Binding(to, b.turns()) : b));
                        yield new io.github.leadassimilator.reticulum.graph.NodeSettings.InstanceSettings(c.sheet(), bindings);
                    }
                    default -> null;
                };
                if (moved != null) {
                    sheet.replace(node.with(moved));
                }
            }
        }
        network.identified().replaceAll(identify -> identify.device().filter(from::equals).isPresent()
                ? new Identify(identify.kind(), identify.connector(), java.util.Optional.of(to), identify.face(), identify.color(), identify.since())
                : identify);
        // Its charts too (one the new device has already is kept once); their history starts again.
        java.util.Set<String> charted = new java.util.HashSet<>();
        network.charts().replaceAll(c -> c.device().filter(from::equals).isPresent() ? new ChartSeries(c.kind(), c.id(), java.util.Optional.of(to), c.resource()) : c);
        network.charts().removeIf(c -> !charted.add(c.key()));
        network.deviceMap().remove(from);
        changed(server, network);
        return true;
    }

    /**
     * True when the network holds nothing a player made: no nodes on any sheet (an empty top sheet, which every
     * network has, doesn't count), no devices, connectors, filter templates or overflow.
     */
    public static boolean isEmpty(Network network) {
        return network.sheets().stream().allMatch(sheet -> sheet.root() && sheet.nodes().isEmpty()) && network.devices().isEmpty()
                && network.connectors().isEmpty() && network.filters().isEmpty() && network.overflow().isEmpty();
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
     * identifies, charts, and device and face names (where {@code into} has none). {@code from} is removed, and blocks still
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
        // The absorbed network's root becomes a sheet named after it, placed in this one's root with all it holds.
        io.github.leadassimilator.reticulum.graph.Sheet fromRoot = from.root();
        fromRoot.setName(from.name());
        fromRoot.setRoot(false);
        into.sheets().addAll(from.sheets());
        into.ensureTree();
        // Switched off, it stays off: as a sheet now, by its sheet node's switch.
        Network.Placement placed = into.placement(fromRoot.id());
        if (!from.enabled() && placed != null) {
            placed.parent().replace(placed.node().withEnabled(false));
        }
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
        from.signalMemory().forEach(into.signalMemory()::putIfAbsent);
        for (io.github.leadassimilator.reticulum.network.Identify identify : from.identified()) {
            if (into.identified().size() < Limits.IDENTIFIES && into.identified().stream().noneMatch(identify::sameTarget)) {
                into.identified().add(identify);
            }
        }
        for (ChartSeries chart : from.charts()) {
            if (into.charts().size() < Limits.CHARTS && into.charts().stream().noneMatch(c -> c.key().equals(chart.key()))) {
                into.charts().add(chart);
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
