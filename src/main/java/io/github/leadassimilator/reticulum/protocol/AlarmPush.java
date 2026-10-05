package io.github.leadassimilator.reticulum.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.leadassimilator.reticulum.engine.Alarms;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tells each player what is going off on their networks (for the network tool's color and sound), and, asked by a
 * tool in Status mode, where: markers on the blocks with something wrong.
 */
public final class AlarmPush {
    /** Markers further away than this aren't sent. */
    private static final int MARKER_DISTANCE = 256;
    private static final int MARKER_LIMIT = 256;
    /** Per player, the states last sent, so nothing is sent while they stay the same. */
    private static final Map<UUID, Map<UUID, Integer>> SENT = new HashMap<>();
    /** Per player, the tick markers were last sent. */
    private static final Map<UUID, Long> LAST_MARKERS = new HashMap<>();

    private AlarmPush() {
    }

    /** A player logging in gets the states afresh (their client forgot them on logging out), and markers at once. */
    public static void onLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        SENT.remove(event.getEntity().getUUID());
        LAST_MARKERS.remove(event.getEntity().getUUID());
    }

    /** A server stopping (in single player, the world closing) forgets everything: the next counts ticks from 0. */
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SENT.clear();
        LAST_MARKERS.clear();
        Alarms.forgetAll();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            Map<UUID, Integer> states = states(server, player);
            if (!states.equals(SENT.get(player.getUUID()))) {
                SENT.put(player.getUUID(), states);
                PacketDistributor.sendToPlayer(player, new Payloads.AlarmStates(states));
            }
        }
        SENT.keySet().retainAll(online);
        LAST_MARKERS.keySet().retainAll(online);
    }

    /**
     * The worst alarm going off (0 a warning, 1 critical) on each network the player belongs to (owner or member, or one
     * nobody owns), plus any other they may view (the network tool may be linked to it). Networks with nothing going off
     * are left out.
     */
    /** The most networks' alarm states sent (the packet's own limit is 4096). */
    private static final int STATES_LIMIT = 4096;

    private static Map<UUID, Integer> states(MinecraftServer server, ServerPlayer player) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Network network : NetworkManager.data(server).networks()) {
            if (!Security.could(network, player, io.github.leadassimilator.reticulum.security.Role.VIEWER)) {
                continue;
            }
            AlarmSeverity worst = Alarms.worst(Alarms.cached(server, network));
            // Within what the packet carries (an operator seeing every network on a huge server).
            if (worst != null && out.size() < STATES_LIMIT) {
                out.put(network.id(), (worst == AlarmSeverity.CRITICAL ? 1 : 0) | (belongs(network, player) ? 0 : 2));
            }
        }
        return out;
    }

    /** Whether the player is the network's owner or a member (or nobody owns it): its alarms are theirs. */
    private static boolean belongs(Network network, ServerPlayer player) {
        return network.owner() == null || player.getUUID().equals(network.owner()) || network.members().containsKey(player.getUUID());
    }

    // ---------------------------------------------------------------- markers

    /** Where things are wrong near the player, on networks they may view: offline devices and connectors, and what feeds alarms. */
    static void sendMarkers(ServerPlayer player) {
        MinecraftServer server = player.server;
        // The tool asks every two seconds; asked faster, the rest are ignored.
        Long last = LAST_MARKERS.get(player.getUUID());
        if (last != null && server.getTickCount() - last < 20) {
            return;
        }
        LAST_MARKERS.put(player.getUUID(), (long) server.getTickCount());
        List<Payloads.StatusMarker> markers = new ArrayList<>();
        Set<String> marked = new HashSet<>();
        for (Network network : NetworkManager.data(server).networks()) {
            if (!Security.could(network, player, io.github.leadassimilator.reticulum.security.Role.VIEWER) || markers.size() >= MARKER_LIMIT) {
                continue;
            }
            NetworkRuntime runtime = NetworkTicker.runtime(network.id());
            // Beacons showing this network's alarms carry them all (the client cycles through them): alarm nodes have no
            // place in the world of their own.
            if (NetworkTicker.isRunning(server, network)) {
                List<Alarms.Alarm> alarms = Alarms.cached(server, network);
                for (DeviceRecord device : alarms.isEmpty() ? List.<DeviceRecord>of() : network.devices()) {
                    var level = server.getLevel(device.pos().dimension());
                    if (level != null && level.isLoaded(device.pos().pos())
                            && level.getBlockEntity(device.pos().pos()) instanceof io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity beacon
                            && beacon.mode() == io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity.Mode.NETWORK) {
                        // Whatever the beacon is set to show: the tool in Status mode sees it all (the beacon's own text steps
                        // aside for the marker on the client).
                        var watch = beacon.watch(level);
                        if (watch.network() == network && watch.reachable()) {
                            for (Alarms.Alarm alarm : alarms.subList(0, Math.min(10, alarms.size()))) {
                                add(player, markers, marked, device.pos(), alarm.severity() == AlarmSeverity.CRITICAL ? 1 : 0, alarm.message());
                            }
                        }
                    }
                }
            }
            // What alarms going off read: the devices behind them, marked in the alarm's color, with its message.
            for (Alarms.Source source : Alarms.sources(server, network)) {
                add(player, markers, marked, source.device(), source.severity() == AlarmSeverity.CRITICAL ? 1 : 0, source.message());
            }
            // Problems about a device's node (one device with two nodes, say): the device, with what is wrong.
            for (var problem : runtime.compiled(network.root(), network).errors()) {
                GlobalPos device = problem.node() == null ? null : deviceOf(network, runtime.compiled(network.root(), network).owner(problem.node()));
                if (device != null) {
                    add(player, markers, marked, device, 0, problem.message());
                }
            }
            boolean running = NetworkTicker.isRunning(server, network);
            for (DeviceRecord device : network.devices()) {
                String why = device.state() == DeviceRecord.State.ORPHANED ? "orphaned"
                        : device.state() == DeviceRecord.State.MISSING ? "missing"
                        : running && runtime.outOfRange(device.pos()) ? "unreachable" : null;
                if (why != null) {
                    String name = device.name() != null ? device.name() : device.blockId().getPath().replace('_', ' ');
                    add(player, markers, marked, device.pos(), 0, name + " — " + why);
                }
            }
            if (running) {
                for (ConnectorRecord connector : network.connectors()) {
                    if (runtime.reach(connector.id()) == NetworkRuntime.Reach.OUT_OF_RANGE) {
                        add(player, markers, marked, connector.pos(), 0, (connector.name() != null ? connector.name() : "Connector") + " — unreachable");
                    }
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new Payloads.StatusMarkers(markers));
    }

    /** The block a node on one of the network's sheets stands for (a device node or a Level sensor's), or null. */
    @javax.annotation.Nullable
    private static GlobalPos deviceOf(Network network, UUID node) {
        for (var sheet : network.sheets()) {
            var found = sheet.blueprint() ? null : sheet.node(node);
            if (found != null) {
                return switch (found.settings()) {
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings device -> device.device();
                    case io.github.leadassimilator.reticulum.graph.NodeSettings.LevelSettings level -> level.device();
                    default -> null;
                };
            }
        }
        return null;
    }

    private static void add(ServerPlayer player, List<Payloads.StatusMarker> markers, Set<String> marked, GlobalPos pos, int severity, String label) {
        if (markers.size() >= MARKER_LIMIT || !pos.dimension().equals(player.level().dimension())
                || pos.pos().distSqr(player.blockPosition()) > (double) MARKER_DISTANCE * MARKER_DISTANCE || !marked.add(pos.pos().asLong() + "/" + label)) {
            return;
        }
        markers.add(new Payloads.StatusMarker(pos.pos(), severity, label.length() > 80 ? label.substring(0, 80) : label));
    }
}
