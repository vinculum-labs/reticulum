package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.NodeSettings.AlarmSeverity;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;

/**
 * What is going off on a network: its alarm nodes, and the built-in alarms (no power, problems keeping flows from
 * running, devices offline, no controller). One list for the editor's tabs and dashboard, beacons and the network tool.
 */
public final class Alarms {
    private Alarms() {
    }

    /**
     * One alarm. {@code sheet} and {@code node}: where the editor shows it (for a node in a placed blueprint, the
     * blueprint's node); null for a built-in alarm about the whole network. {@code since}: wall clock ms.
     */
    public record Alarm(String key, AlarmSeverity severity, String message, @Nullable UUID sheet, @Nullable UUID node, long since) {
    }

    /** At most this many are listed (the worst and oldest first). */
    public static final int LIMIT = 50;

    public static List<Alarm> of(MinecraftServer server, Network network) {
        NetworkRuntime runtime = NetworkTicker.runtime(network.id());
        List<Alarm> out = new ArrayList<>();
        Set<String> builtIn = new HashSet<>();
        boolean running = NetworkTicker.isRunning(server, network);
        if (!running) {
            runtime.forgetAlarms();
        }
        if (network.enabled() && network.controller() == null) {
            out.add(builtIn(runtime, builtIn, "no_controller", AlarmSeverity.WARNING, "No controller — nothing runs"));
        }
        if (running) {
            NetworkRuntime.PowerState power = runtime.powerState(server, network);
            if (power.enabled() && !power.powered()) {
                out.add(builtIn(runtime, builtIn, "no_power", AlarmSeverity.CRITICAL, "No power — only energy flows run"));
            }
            int problems = runtime.compiled(network.root(), network).errors().size();
            if (problems > 0) {
                out.add(builtIn(runtime, builtIn, "problems", AlarmSeverity.WARNING,
                        problems + (problems == 1 ? " problem keeps" : " problems keep") + " flows from running"));
            }
            long offline = network.devices().stream()
                    .filter(d -> d.state() != DeviceRecord.State.ACTIVE || runtime.outOfRange(d.pos())).count();
            if (offline > 0) {
                out.add(builtIn(runtime, builtIn, "offline", AlarmSeverity.WARNING,
                        offline + (offline == 1 ? " device is" : " devices are") + " offline"));
            }
            for (Signals.Raised raised : runtime.raisedAlarms()) {
                UUID id = raised.node().node().id();
                String message = raised.alarm().message().isBlank()
                        ? raised.node().node().label().orElse("Alarm") : raised.alarm().message();
                out.add(new Alarm(id.toString(), raised.alarm().severity(), message, raised.sheet().sheetOf(id),
                        raised.sheet().owner(id), raised.at()));
            }
        }
        runtime.builtInSince.keySet().retainAll(builtIn);
        out.sort(Comparator.comparing((Alarm a) -> a.severity() != AlarmSeverity.CRITICAL).thenComparingLong(Alarm::since));
        return out.size() > LIMIT ? List.copyOf(out.subList(0, LIMIT)) : out;
    }

    private record Cached(long tick, List<Alarm> alarms) {
    }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    /** Forgets every network's list (the server stopped). */
    public static void forgetAll() {
        CACHE.clear();
    }

    /** {@link #of}, worked out at most once per server tick for each network. */
    public static List<Alarm> cached(MinecraftServer server, Network network) {
        long tick = server.getTickCount();
        // Lists of deleted networks don't pile up.
        if (CACHE.size() > NetworkManager.data(server).networks().size() + 16) {
            CACHE.keySet().retainAll(NetworkManager.data(server).networks().stream().map(Network::id).toList());
        }
        Cached cached = CACHE.get(network.id());
        if (cached == null || cached.tick() != tick) {
            cached = new Cached(tick, of(server, network));
            CACHE.put(network.id(), cached);
        }
        return cached.alarms();
    }

    /** A device an alarm going off reads (through any signal nodes in between): where the trouble is. */
    public record Source(GlobalPos device, AlarmSeverity severity, String message) {
    }

    /** The devices behind every alarm node going off, found by following its wires upstream to Level sensors and redstone reads. */
    public static List<Source> sources(MinecraftServer server, Network network) {
        List<Source> out = new ArrayList<>();
        if (!NetworkTicker.isRunning(server, network)) {
            return out;
        }
        for (Signals.Raised raised : NetworkTicker.runtime(network.id()).raisedAlarms()) {
            Map<UUID, CompiledSheet.SignalNode> byId = new HashMap<>();
            raised.sheet().signals().forEach(node -> byId.put(node.node().id(), node));
            String message = raised.alarm().message().isBlank() ? raised.node().node().label().orElse("Alarm") : raised.alarm().message();
            ArrayDeque<UUID> todo = new ArrayDeque<>(raised.node().inputs().stream().flatMap(List::stream).toList());
            Set<UUID> seen = new HashSet<>();
            while (!todo.isEmpty() && seen.size() < 256) {
                UUID id = todo.poll();
                CompiledSheet.SignalNode node = byId.get(id);
                if (node == null || !seen.add(id)) {
                    continue;
                }
                switch (node.node().settings()) {
                    case NodeSettings.LevelSettings level ->
                            out.add(new Source(level.device(), raised.alarm().severity(), message));
                    case NodeSettings.RedstoneInSettings in ->
                            out.add(new Source(in.device(), raised.alarm().severity(), message));
                    default -> node.inputs().forEach(todo::addAll);
                }
            }
        }
        return out;
    }

    /** The worst severity going off, or null when nothing is. */
    @Nullable
    public static AlarmSeverity worst(List<Alarm> alarms) {
        return alarms.isEmpty() ? null : alarms.getFirst().severity();
    }

    private static Alarm builtIn(NetworkRuntime runtime, Set<String> seen, String key, AlarmSeverity severity, String message) {
        seen.add(key);
        long since = runtime.builtInSince.computeIfAbsent(key, k -> System.currentTimeMillis());
        return new Alarm(key, severity, message, null, null, since);
    }
}
