package io.github.leadassimilator.reticulum.protocol;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.security.NetworkAdmin;
import io.github.leadassimilator.reticulum.security.Access;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Passwords;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side of the in-game quick screens: builds views and applies the edits they send. */
public final class Screens {
    public static final int MAX_NAME_LENGTH = io.github.leadassimilator.reticulum.network.Limits.NAME;

    private Screens() {
    }

    /** The ID buttons in the connector screens: the connector itself, the device on a side, or one face of it. */
    public static void toggleIdentify(ServerPlayer player, Payloads.ToggleIdentify payload) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = payload.pos();
        if (!(Access.reach(player, pos) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = Access.require(player, NetworkManager.network(level.getServer(), Topology.networkOf(level, connector)), Role.USER);
        if (network == null) {
            return;
        }
        Identify target;
        if (payload.side().isEmpty()) {
            target = Identify.connector(connector.connectorId(), 0);
        } else {
            GlobalPos device = Devices.global(level, pos.relative(payload.side().get()));
            target = payload.face().isPresent() ? Identify.face(device, payload.face().get(), 0) : Identify.device(device, 0);
        }
        Identifies.toggle(level.getServer(), network, target);
        refresh(player, pos);
    }

    /** Opens a network block's screen for the player. */
    /**
     * A Status Beacon's screen, for a player in reach. Anyone may look; changing it takes use of the network it is a
     * device of (any player, on none).
     */
    public static void openBeacon(ServerPlayer player, BlockPos pos) {
        openBeacon(player, pos, false);
    }

    private static void openBeacon(ServerPlayer player, BlockPos pos, boolean refresh) {
        if (!io.github.leadassimilator.reticulum.security.Access.inReach(player, pos)
                || !(player.serverLevel().getBlockEntity(pos) instanceof io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity beacon)) {
            return;
        }
        var watch = beacon.watch(player.serverLevel());
        io.github.leadassimilator.reticulum.network.Network network = watch.network();
        boolean canView = network != null && io.github.leadassimilator.reticulum.security.Security.canView(network, player);
        java.util.List<String> alarms = new java.util.ArrayList<>();
        if (beacon.mode() == io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity.Mode.NETWORK && canView && watch.reachable()) {
            for (var alarm : io.github.leadassimilator.reticulum.engine.Alarms.cached(player.server, network)) {
                if (alarms.size() < 8) {
                    alarms.add((alarm.severity() == io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity.CRITICAL ? "!" : "?") + alarm.message());
                }
            }
        }
        boolean canEdit = network == null || io.github.leadassimilator.reticulum.security.Security.canUse(network, player);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new Payloads.OpenBeaconScreen(pos, beaconStatus(player, beacon, watch),
                beacon.settings(), player.serverLevel().getBestNeighborSignal(pos), alarms, canEdit, refresh));
    }

    /** One line on what a beacon shows: the network it watches and whether it reaches it, or the signal it gets. */
    public static net.minecraft.network.chat.Component beaconStatus(ServerPlayer player,
            io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity beacon,
            io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity.Watch watch) {
        if (beacon.mode() == io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity.Mode.REDSTONE) {
            return net.minecraft.network.chat.Component.translatable("lookat.reticulum.beacon_signal",
                    player.serverLevel().getBestNeighborSignal(beacon.getBlockPos()));
        }
        if (watch.network() == null) {
            return net.minecraft.network.chat.Component.translatable("lookat.reticulum.beacon_no_connector");
        }
        String name = io.github.leadassimilator.reticulum.security.Security.canView(watch.network(), player) ? watch.network().name() : "?";
        return net.minecraft.network.chat.Component.translatable(watch.reachable() ? "lookat.reticulum.beacon_watching" : "lookat.reticulum.beacon_unreachable", name);
    }

    /** Whether the player may change a beacon: any player, unless it is a device of a network they can't use. */
    public static boolean mayChangeBeacon(ServerPlayer player, io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity beacon) {
        if (player.isSpectator()) {
            return false;
        }
        io.github.leadassimilator.reticulum.network.Network network = beacon.watch(player.serverLevel()).network();
        if (network != null && !io.github.leadassimilator.reticulum.security.Security.canUse(network, player)) {
            // Not named to someone who can't see it.
            player.displayClientMessage(Access.refusal(network, player, Role.USER), true);
            return false;
        }
        return true;
    }

    static void setBeaconSettings(ServerPlayer player, Payloads.BeaconSettings payload) {
        if (!io.github.leadassimilator.reticulum.security.Access.inReach(player, payload.pos())
                || !(player.serverLevel().getBlockEntity(payload.pos()) instanceof io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity beacon)) {
            return;
        }
        if (!mayChangeBeacon(player, beacon)) {
            return;
        }
        beacon.applySettings(payload.settings());
        // Sent back as kept (within the server's limits), and a new mode shows something else. Only to a screen still
        // open: closing it sends what was typed, and the answer mustn't open it again.
        openBeacon(player, payload.pos(), true);
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        // Claimed: not opened this time, so the message saying so (in chat) isn't hidden behind the screen.
        if (claim(player, pos)) {
            return;
        }
        send(player, pos, false);
    }

    /**
     * A network with no owner (its controller placed by a machine, a schematic or a Deployer) becomes the player's who
     * first opens its controller, as if they had placed it. Returns whether it did.
     */
    private static boolean claim(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer || player.isSpectator()
                || !(level.getBlockEntity(pos) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity controller)) {
            return false;
        }
        Network network = NetworkManager.network(level.getServer(), controller.networkId());
        if (network == null || network.owner() != null) {
            return false;
        }
        network.setOwner(player.getUUID(), player.getGameProfile().getName());
        NetworkManager.changed(level.getServer(), network);
        // It stays as open as it was (others may already use it), but they are told when that is to anyone.
        boolean open = network.accessMode() == io.github.leadassimilator.reticulum.security.AccessMode.PUBLIC;
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(open ? "message.reticulum.network_claimed_public"
                : "message.reticulum.network_claimed", network.name()), false);
        return true;
    }

    /** New contents for a screen after a change made in it; the client ignores it if the screen was closed meanwhile. */
    static void refresh(ServerPlayer player, BlockPos pos) {
        send(player, pos, true);
    }

    private static void send(ServerPlayer player, BlockPos pos, boolean refresh) {
        ServerLevel level = player.serverLevel();
        NetworkBlockEntity be = Topology.blockEntity(level, pos);
        if (be == null) {
            return;
        }
        Network network = NetworkManager.network(level.getServer(), Topology.networkOf(level, be));
        if (network != null && Access.require(player, network, Role.VIEWER) == null) {
            return;
        }
        if (be instanceof ControllerBlockEntity) {
            if (network == null) {
                // Its network was deleted: give it one again (an orphaned neighbor's, or a new one).
                network = NetworkManager.network(level.getServer(), Topology.reclaimController(level, pos, player));
            }
            if (network != null && Security.canView(network, player)) {
                PacketDistributor.sendToPlayer(player, new Payloads.OpenControllerScreen(controllerView(player, pos, network), refresh));
            }
        } else {
            PacketDistributor.sendToPlayer(player, new Payloads.OpenBlockScreen(blockView(player, level, pos, be, network), refresh));
        }
    }

    private static Views.ControllerView controllerView(ServerPlayer player, BlockPos pos, Network network) {
        return new Views.ControllerView(pos, network.id(), network.name(), network.ownerName(), network.accessMode(), network.passwordHash() != null,
                network.connectors().size(), network.devices().size(), NetworkManager.orphanCount(network),
                Security.canEdit(network, player), Security.canAdmin(network, player),
                player.serverLevel().getBlockEntity(pos) instanceof ControllerBlockEntity controller ? controller.antennaTier() : 0,
                network.enabled(), io.github.leadassimilator.reticulum.engine.NetworkTicker.isRunning(player.server, network),
                network.switches().redstone(), sheetEntries(player, network), overflowLines(player, network), powerLine(player, network));
    }

    /** "Power: 52,000 / 1,000,000 FE (24 FE/t)", or empty when power is free. */
    private static String powerLine(ServerPlayer player, Network network) {
        var state = io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(network.id()).powerState(player.server, network);
        if (!state.enabled()) {
            return "";
        }
        return Component.translatable(state.powered() ? "screen.reticulum.power" : "screen.reticulum.power_out",
                String.format("%,d", state.stored()), String.format("%,d", state.capacity()), String.format("%.1f", state.upkeep())).getString();
    }

    private static List<Views.SheetEntry> sheetEntries(ServerPlayer player, Network network) {
        var runtime = io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(network.id());
        var compiled = runtime.compiled(network.root(), network);
        List<Views.SheetEntry> entries = new ArrayList<>();
        // Down the tree, each sheet followed by the ones inside it; the root is the network's own switch.
        java.util.Map<java.util.UUID, Network.Placement> placements = network.placements();
        addEntries(network, placements, network.root(), 0, runtime, compiled, entries);
        return entries;
    }

    private static void addEntries(Network network, java.util.Map<java.util.UUID, Network.Placement> placements,
            io.github.leadassimilator.reticulum.graph.Sheet parent, int depth, io.github.leadassimilator.reticulum.engine.NetworkRuntime runtime,
            io.github.leadassimilator.reticulum.engine.CompiledSheet compiled, List<Views.SheetEntry> entries) {
        if (depth > 32) {
            return;
        }
        for (io.github.leadassimilator.reticulum.graph.Node node : parent.nodes()) {
            if (!(node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.SheetSettings child)) {
                continue;
            }
            io.github.leadassimilator.reticulum.graph.Sheet sheet = network.sheet(child.sheet());
            Network.Placement placement = placements.get(child.sheet());
            if (sheet == null || placement == null || placement.node() != node && !placement.node().id().equals(node.id())) {
                continue;
            }
            entries.add(new Views.SheetEntry(sheet.id(), sheet.name(), depth, node.enabled(), node.enabled() && !network.runs(sheet),
                    runtime.sheetsOff().contains(sheet.id()), compiled.errorsFor(sheet.id()).size()));
            addEntries(network, placements, sheet, depth + 1, runtime, compiled, entries);
        }
    }

    private static List<String> overflowLines(ServerPlayer player, Network network) {
        List<String> lines = new ArrayList<>();
        network.overflowItems().forEach(stack -> lines.add(stack.getCount() + "× " + stack.getHoverName().getString()));
        network.overflowFluids().forEach(fluid -> lines.add(fluid.getAmount() + " mB " + fluid.getHoverName().getString()));
        network.overflow().chemicals().forEach((id, amount) -> lines.add(amount + " mB " + id));
        return lines;
    }

    /** The controller screen's Network tab: the network's button and redstone mode, and each sheet's button. */
    static void controllerSwitch(ServerPlayer player, Payloads.ControllerSwitch payload) {
        if (!(Access.reach(player, payload.pos()) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = Access.require(player, Topology.network(player.serverLevel(), payload.pos()), Role.EDITOR);
        if (network == null) {
            return;
        }
        switch (payload.what()) {
            case Payloads.ControllerSwitch.NETWORK -> network.setEnabled(payload.value() != 0);
            case Payloads.ControllerSwitch.REDSTONE -> {
                var modes = io.github.leadassimilator.reticulum.network.Switches.Redstone.values();
                network.switches().setRedstone(modes[Math.floorMod(payload.value(), modes.length)]);
            }
            // Only the sheets the screen lists: blueprints run as part of the sheets that use them.
            case Payloads.ControllerSwitch.SHEET -> payload.sheet().map(network::sheet).filter(sheet -> !sheet.blueprint())
                    .ifPresent(sheet -> network.setSheetEnabled(sheet, payload.value() != 0));
            default -> {
            }
        }
        NetworkManager.changed(player.server, network);
        refresh(player, payload.pos());
    }

    private static Views.BlockView blockView(ServerPlayer player, ServerLevel level, BlockPos pos, NetworkBlockEntity be, @Nullable Network network) {
        boolean connector = be instanceof ConnectorBlockEntity;
        List<Views.SideView> sides = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            sides.add(sideView(level, pos, be, dir, network));
        }
        String defaultName = connector ? ConnectorRecord.defaultName(pos) : "";
        String customName = "";
        if (be instanceof ConnectorBlockEntity c && network != null) {
            ConnectorRecord record = network.connector(c.connectorId());
            if (record != null && record.name() != null) {
                customName = record.name();
            }
        }
        boolean canEdit = network == null || Security.canEdit(network, player);
        boolean identified = network != null && be instanceof ConnectorBlockEntity c2
                && Identifies.isOn(network, Identify.connector(c2.connectorId(), 0));
        return new Views.BlockView(pos, connector, network == null ? "" : network.name(), defaultName, customName, sides, canEdit,
                java.util.Optional.of(Topology.rebindHint(level, pos)), identified);
    }

    private static Views.SideView sideView(ServerLevel level, BlockPos pos, NetworkBlockEntity be, Direction dir, @Nullable Network network) {
        BlockPos next = pos.relative(dir);
        // A connector facing another network's controller: a device it gives redstone to (attach or detach), not a joint.
        boolean controllerDevice = be instanceof ConnectorBlockEntity && level.getBlockEntity(next) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity
                && !Topology.connected(level, pos, dir) && Topology.reachesDevice(level, pos, dir);
        Topology.JointState joint = controllerDevice ? null : Topology.jointState(level, pos, dir);
        if (joint != null) {
            Views.JointView view = switch (joint) {
                case CONNECTED -> Views.JointView.CONNECTED;
                case DISCONNECTED -> Views.JointView.DISCONNECTED;
                case CONFLICT -> Views.JointView.CONFLICT;
            };
            return new Views.SideView(dir, Views.SideKind.JOINT, view, true, Devices.icon(level, next),
                    level.getBlockState(next).getBlock().getName().getString(), "", List.of(), 0);
        }
        if (be instanceof ConnectorBlockEntity connector && (controllerDevice || Devices.isDevice(level, next, dir.getOpposite()))) {
            DeviceRecord record = network == null ? null : network.device(Devices.global(level, next));
            List<String> faces = new ArrayList<>();
            for (FaceKey face : FaceKey.values()) {
                String name = record == null ? null : record.faceName(face);
                faces.add(name == null ? "" : name);
            }
            int identified = 0;
            if (network != null) {
                GlobalPos device = Devices.global(level, next);
                if (Identifies.isOn(network, Identify.device(device, 0))) {
                    identified |= Views.SideView.DEVICE_BIT;
                }
                for (FaceKey face : FaceKey.values()) {
                    if (Identifies.isOn(network, Identify.face(device, face, 0))) {
                        identified |= 1 << face.ordinal();
                    }
                }
            }
            return new Views.SideView(dir, Views.SideKind.DEVICE, Views.JointView.DISCONNECTED, connector.isDeviceEnabled(dir),
                    Devices.icon(level, next), Devices.defaultName(level, next),
                    record == null || record.name() == null ? "" : record.name(), faces, identified);
        }
        return new Views.SideView(dir, Views.SideKind.NONE, Views.JointView.DISCONNECTED, false, ItemStack.EMPTY, "", "", List.of(), 0);
    }

    /** Answers the built-in HUD. Limited to blocks the player could plausibly be looking at. */
    static void lookAt(ServerPlayer player, Payloads.LookRequest payload) {
        if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > LOOK_DISTANCE * LOOK_DISTANCE || !player.serverLevel().isLoaded(payload.pos())) {
            return;
        }
        var lines = io.github.leadassimilator.reticulum.lookat.LookAt.describe(player.serverLevel(), payload.pos(), payload.face(), player);
        PacketDistributor.sendToPlayer(player, new Payloads.LookResponse(payload.pos(), lines.size() > 16 ? lines.subList(0, 16) : lines));
    }

    // ---------------------------------------------------------------- edits

    /** How far away the built-in HUD answers for (what the player could be looking at). */
    private static final int LOOK_DISTANCE = 32;

    /** Checks the player can reach the block; every edit starts here. */
    @Nullable
    private static NetworkBlockEntity reachable(ServerPlayer player, BlockPos pos) {
        return Access.reach(player, pos);
    }

    static void toggleSide(ServerPlayer player, Payloads.ToggleSide payload) {
        if (reachable(player, payload.pos()) == null) {
            return;
        }
        Component message = Topology.toggle(player.serverLevel(), payload.pos(), payload.side(), player);
        player.displayClientMessage(message, true);
        refresh(player, payload.pos());
    }

    static void renameConnector(ServerPlayer player, Payloads.RenameConnector payload) {
        if (!(reachable(player, payload.pos()) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = editableNetwork(player, connector);
        if (network == null) {
            return;
        }
        ConnectorRecord record = network.connector(connector.connectorId());
        if (record != null) {
            record.setName(clip(payload.name()));
            NetworkManager.changed(player.server, network);
        }
        refresh(player, payload.pos());
    }

    static void renameDevice(ServerPlayer player, Payloads.RenameDevice payload) {
        if (!(reachable(player, payload.pos()) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = editableNetwork(player, connector);
        if (network == null) {
            return;
        }
        GlobalPos device = Devices.global(player.level(), payload.pos().relative(payload.side()));
        DeviceRecord record = network.device(device);
        if (record == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_device"), true);
            return;
        }
        if (payload.face().isPresent()) {
            record.setFaceName(payload.face().get(), clip(payload.name()));
        } else {
            record.setName(clip(payload.name()));
        }
        NetworkManager.changed(player.server, network);
        refresh(player, payload.pos());
    }

    static void controllerSettings(ServerPlayer player, Payloads.ControllerSettings payload) {
        if (!(reachable(player, payload.pos()) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = Access.require(player, Topology.network(player.serverLevel(), payload.pos()), Role.EDITOR);
        if (network == null) {
            return;
        }
        network.setName(clip(payload.name()));
        if (Security.canAdmin(network, player)) {
            try {
                if (!payload.password().isEmpty()) {
                    NetworkAdmin.setPassword(network, player.getUUID(), payload.password());
                }
                NetworkAdmin.setAccess(network, payload.mode());
            } catch (NetworkAdmin.Refused e) {
                player.displayClientMessage(e.message(), true);
            }
        }
        NetworkManager.changed(player.server, network);
        refresh(player, payload.pos());
    }

    @Nullable
    private static Network editableNetwork(ServerPlayer player, NetworkBlockEntity be) {
        return Access.require(player, NetworkManager.network(player.server, Topology.networkOf(player.serverLevel(), be)), Role.EDITOR);
    }

    private static String clip(String name) {
        String value = io.github.leadassimilator.reticulum.network.Limits.name(name);
        return value == null ? "" : value;
    }
}
