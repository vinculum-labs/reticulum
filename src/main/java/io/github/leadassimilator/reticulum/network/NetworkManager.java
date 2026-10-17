package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.filter.FilterRefs;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Sheet;
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
        network.touchShown();
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

    /**
     * A connector taken onto {@code network} without being scanned (its neighbors not loaded): its record with the devices it
     * had, and records for those (active, as it still touches them), from {@code from} where it has one, so their names
     * can follow; the rolling rescan puts it right once the area loads.
     */
    public static void adoptConnector(MinecraftServer server, Network network, UUID connectorId, GlobalPos connectorPos, List<GlobalPos> devices,
            Map<GlobalPos, DeviceRecord> from) {
        ConnectorRecord record = network.connectorMap().computeIfAbsent(connectorId, id -> new ConnectorRecord(id, connectorPos));
        for (GlobalPos device : devices) {
            if (!record.devices().contains(device)) {
                record.devices().add(device);
            }
            DeviceRecord old = from.get(device);
            if (old != null && !network.deviceMap().containsKey(device)) {
                DeviceRecord copy = new DeviceRecord(device, old.blockId());
                copy.setState(DeviceRecord.State.ACTIVE);
                network.deviceMap().put(device, copy);
            }
        }
        changed(server, network);
    }

    /** A connector's devices let go (it was lifted by a contraption): its record stays, with none. */
    public static void releaseAll(MinecraftServer server, Network network, ConnectorRecord record) {
        List<GlobalPos> old = new ArrayList<>(record.devices());
        record.devices().clear();
        for (GlobalPos device : old) {
            releaseDevice(server, network, device);
        }
        changed(server, network);
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
    public static ControllerBlockEntity controller(MinecraftServer server, Network network) {
        GlobalPos at = network.controller();
        ServerLevel level = at == null ? null : server.getLevel(at.dimension());
        if (level == null || !level.isLoaded(at.pos())
                || !(level.getBlockEntity(at.pos()) instanceof ControllerBlockEntity controller)) {
            return null;
        }
        return network(server, controller.networkId()) == network ? controller : null;
    }

    /**
     * Whether any sheet uses the device (a node on it, a node reading it, or a blueprint instance's slot it fills), or a
     * chart does: such a record is kept when the block goes, so its flows and charts can be moved onto the block that
     * replaces it.
     */
    public static boolean inUse(Network network, GlobalPos pos) {
        for (Sheet sheet : network.sheets()) {
            for (Node node : sheet.nodes()) {
                if (node.settings() instanceof NodeSettings.InstanceSettings instance
                        && Stream.concat(instance.bindings().values().stream(), instance.layout().values().stream())
                                .anyMatch(b -> pos.equals(b.device()))) {
                    return true;
                }
                GlobalPos used = switch (node.settings()) {
                    case NodeSettings.DeviceSettings s -> s.device();
                    case NodeSettings.LevelSettings s -> s.device();
                    case NodeSettings.RedstoneInSettings s -> s.device();
                    case NodeSettings.RedstoneOutSettings s -> s.device();
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
        // Its chunk not loaded: not looked at (that would load it); taken as still there, the block not known gone.
        if (level != null && !level.isLoaded(pos.pos())) {
            record.setState(DeviceRecord.State.ORPHANED);
            return;
        }
        boolean present = level != null && (Devices.isDevice(level, pos.pos())
                || level.getBlockEntity(pos.pos()) instanceof ControllerBlockEntity)
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
        for (Sheet sheet : network.sheets()) {
            for (Node node : List.copyOf(sheet.nodes())) {
                NodeSettings moved = switch (node.settings()) {
                    // A blueprint slot keeps its kind of block (its device is only where its layout measures from).
                    case NodeSettings.DeviceSettings d when d.device().equals(from) ->
                            new NodeSettings.DeviceSettings(to, d.inputs(), d.outputs(), d.block());
                    case NodeSettings.LevelSettings l when l.device().equals(from) ->
                            l.reading(to, l.faces());
                    case NodeSettings.RedstoneInSettings r when r.device().equals(from) ->
                            new NodeSettings.RedstoneInSettings(to, r.faces(), r.comparator());
                    case NodeSettings.RedstoneOutSettings r when r.device().equals(from) ->
                            new NodeSettings.RedstoneOutSettings(to, r.faces(), r.whileOff());
                    // An instance whose slot the device filled: the new block fills it, turned the same. One placed in a
                    // blueprint: its slots' layout measures from the new block, as the blueprint's own slots do.
                    case NodeSettings.InstanceSettings c when Stream.concat(
                            c.bindings().values().stream(), c.layout().values().stream()).anyMatch(b -> from.equals(b.device())) -> {
                        UnaryOperator<Map<UUID, NodeSettings.Binding>> relinked = map -> {
                            Map<UUID, NodeSettings.Binding> out = new LinkedHashMap<>();
                            map.forEach((slot, b) -> out.put(slot, from.equals(b.device()) ? new NodeSettings.Binding(to, b.turns()) : b));
                            return out;
                        };
                        yield new NodeSettings.InstanceSettings(c.sheet(), relinked.apply(c.bindings()), relinked.apply(c.layout()));
                    }
                    default -> null;
                };
                if (moved != null) {
                    sheet.replace(node.with(moved));
                }
            }
        }
        network.identified().replaceAll(identify -> identify.device().filter(from::equals).isPresent()
                ? new Identify(identify.kind(), identify.connector(), Optional.of(to), identify.face(), identify.color(), identify.since())
                : identify);
        // Its charts too (one the new device has already is kept once); their history starts again.
        Set<String> charted = new HashSet<>();
        network.charts().replaceAll(c -> c.device().filter(from::equals).isPresent() ? new ChartSeries(c.kind(), c.id(), Optional.of(to), c.resource()) : c);
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
                && network.connectors().isEmpty() && network.filters().isEmpty() && network.overflow().isEmpty()
                && network.members().isEmpty() && network.passwordHash() == null;
    }

    /**
     * Deletes a network. Blocks still carrying its id behave as if they had no network; a controller of a deleted
     * network gets a new one the next time it is opened.
     */
    public static void delete(MinecraftServer server, Network network) {
        // Its controller, there still, remembers whose it was: opened again by someone else, it is theirs again.
        var controller = controller(server, network);
        if (controller != null) {
            controller.rememberOwner(network.owner(), network.ownerName());
        }
        network.bump();
        data(server).remove(network.id());
        NetworkTicker.forget(server, network);
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
    /**
     * Why merging {@code from} into {@code into} would go past a network's limits (sheets, members, filter templates),
     * or null when it fits.
     */
    @Nullable
    public static String mergeRefusal(Network into, Network from) {
        if (into.sheets().size() + from.sheets().size() > Limits.SHEETS) {
            return "together they would have more than " + Limits.SHEETS + " sheets";
        }
        Set<UUID> members = new HashSet<>(into.members().keySet());
        members.addAll(from.members().keySet());
        if (from.owner() != null) {
            members.add(from.owner());
        }
        members.remove(into.owner());
        if (members.size() > Limits.MEMBERS) {
            return "together they would have more than " + Limits.MEMBERS + " members";
        }
        // A template of the same name and filter is one; of the same name and another filter, two (renamed in the merge).
        long templates = into.filters().size() + from.filters().entrySet().stream()
                .filter(e -> !e.getValue().equals(into.filters().get(e.getKey()))).count();
        if (templates > Limits.FILTER_TEMPLATES) {
            return "together they would have more than " + Limits.FILTER_TEMPLATES + " filter templates";
        }
        return null;
    }

    /** Whether any filter or template of the network names template {@code name}. */
    private static boolean mentioned(Network network, String name) {
        if (network.filters().values().stream().anyMatch(e -> FilterRefs.mentions(e, name))) {
            return true;
        }
        for (Sheet sheet : network.sheets()) {
            for (Node node : sheet.nodes()) {
                for (String filter : FilterRefs.filters(node.settings())) {
                    if (FilterRefs.mentions(filter, name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void absorb(MinecraftServer server, Network into, Network from, Role cap) {
        if (into == from) {
            return;
        }
        Role most = cap.atLeast(Role.ADMIN) ? Role.ADMIN : cap;
        // The absorbed network's root becomes a sheet named after it, placed in this one's root with all it holds.
        Sheet fromRoot = from.root();
        fromRoot.setName(from.name());
        fromRoot.setRoot(false);
        for (Map.Entry<String, String> template : List.copyOf(from.filters().entrySet())) {
            String name = template.getKey();
            String here = into.filters().get(name);
            if (here == null || here.equals(template.getValue())) {
                continue;
            }
            String renamed = name;
            // Not a name taken, nor one a filter still names (it would start matching this one).
            for (int n = 2; into.filters().containsKey(renamed) || from.filters().containsKey(renamed) || mentioned(into, renamed)
                    || mentioned(from, renamed); n++) {
                String suffix = " " + n;
                renamed = (name.length() + suffix.length() > Limits.NAME ? name.substring(0, Limits.NAME - suffix.length()).stripTrailing() : name) + suffix;
            }
            String to = renamed;
            from.filters().remove(name);
            from.filters().put(to, template.getValue());
            from.filters().replaceAll((k, v) -> FilterRefs.rename(v, name, to));
            for (Sheet sheet : from.sheets()) {
                for (Node node : List.copyOf(sheet.nodes())) {
                    var settings = FilterRefs.rename(node.settings(),
                            e -> e == null ? null : FilterRefs.rename(e, name, to));
                    if (!settings.equals(node.settings())) {
                        sheet.replace(node.with(settings));
                    }
                }
            }
            Reticulum.LOGGER.info("Merging {} into {}: its filter template @{} is @{} now ({} has its own @{})",
                    from.name(), into.name(), name, to, into.name(), name);
        }
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
        int leftOut = 0;
        for (Identify identify : from.identified()) {
            if (into.identified().stream().noneMatch(identify::sameTarget)) {
                if (into.identified().size() < Limits.IDENTIFIES) {
                    into.identified().add(identify);
                } else {
                    leftOut++;
                }
            }
        }
        for (ChartSeries chart : from.charts()) {
            if (into.charts().stream().noneMatch(c -> c.key().equals(chart.key()))) {
                if (into.charts().size() < Limits.CHARTS) {
                    into.charts().add(chart);
                } else {
                    leftOut++;
                }
            }
        }
        if (leftOut > 0) {
            Reticulum.LOGGER.warn("Merging {} into {}: {} identifies or charts didn't fit (limits) and were left out",
                    from.name(), into.name(), leftOut);
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
        from.overflowItems().forEach(stack -> OverflowBuffer.addItem(into.overflowItems(), stack));
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
