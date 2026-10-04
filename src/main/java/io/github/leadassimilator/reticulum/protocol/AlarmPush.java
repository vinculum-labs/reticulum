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
    private static Map<UUID, Integer> states(MinecraftServer server, ServerPlayer player) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Network network : NetworkManager.data(server).networks()) {
            if (!Security.canView(network, player)) {
                continue;
            }
            AlarmSeverity worst = Alarms.worst(Alarms.cached(server, network));
            if (worst != null) {
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
        Set<GlobalPos> marked = new HashSet<>();
        for (Network network : NetworkManager.data(server).networks()) {
            if (!Security.canView(network, player) || markers.size() >= MARKER_LIMIT) {
                continue;
            }
            NetworkRuntime runtime = NetworkTicker.runtime(network.id());
            // What alarms going off read: the devices behind them, marked in the alarm's color, with its message.
            for (Alarms.Source source : Alarms.sources(server, network)) {
                add(player, markers, marked, source.device(), source.severity() == AlarmSeverity.CRITICAL ? 1 : 0, source.message());
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

    private static void add(ServerPlayer player, List<Payloads.StatusMarker> markers, Set<GlobalPos> marked, GlobalPos pos, int severity, String label) {
        if (markers.size() >= MARKER_LIMIT || !pos.dimension().equals(player.level().dimension())
                || pos.pos().distSqr(player.blockPosition()) > (double) MARKER_DISTANCE * MARKER_DISTANCE || !marked.add(pos)) {
            return;
        }
        markers.add(new Payloads.StatusMarker(pos.pos(), severity, label.length() > 80 ? label.substring(0, 80) : label));
    }
}
