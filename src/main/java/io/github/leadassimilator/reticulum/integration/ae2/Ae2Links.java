package io.github.leadassimilator.reticulum.integration.ae2;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.engine.MeLinker;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Turns ME link wires into AE2 grid connections between connector endpoints, like a quantum bridge without the
 * bridge. A link is made while both endpoints exist, are loaded and are in reach, and removed as soon as its wire
 * goes, its sheet stops, or either end goes away.
 */
public final class Ae2Links implements MeLinker {
    /** One end of a link: a connector side. Links are kept with their ends in a fixed order. */
    private record End(UUID connector, Direction side) {
    }

    private record Key(End a, End b) {
        static Key of(CompiledSheet.MeLink link) {
            End a = new End(link.connectorA(), link.sideA());
            End b = new End(link.connectorB(), link.sideB());
            return compare(a, b) <= 0 ? new Key(a, b) : new Key(b, a);
        }

        private static int compare(End x, End y) {
            int c = x.connector.compareTo(y.connector);
            return c != 0 ? c : Integer.compare(x.side.ordinal(), y.side.ordinal());
        }
    }

    private final Map<Key, IGridConnection> connections = new HashMap<>();
    /** Links that failed to connect, so the log isn't flooded; retried every so often. */
    private final Set<Key> failed = new HashSet<>();
    private long updates;

    private Ae2Links() {
    }

    public static MeLinker create() {
        return new Ae2Links();
    }

    @Override
    public void update(MinecraftServer server, Network network, Collection<CompiledSheet.MeLink> wanted, Predicate<UUID> inReach) {
        updates++;
        if (updates % 200 == 0) {
            failed.clear();
        }
        Set<Key> keep = new HashSet<>();
        for (CompiledSheet.MeLink link : wanted) {
            Key key = Key.of(link);
            if (key.a.equals(key.b) || !inReach.test(key.a.connector) || !inReach.test(key.b.connector)) {
                continue;
            }
            IGridNode a = node(server, network, key.a);
            IGridNode b = node(server, network, key.b);
            if (a == null || b == null) {
                // An AE2 machine ready after the connector sends it no block update: look again now and then.
                if (updates % 20 == 0) {
                    for (End end : List.of(key.a, key.b)) {
                        MeEndpoints endpoints = endpoints(server, network, end.connector);
                        if (endpoints != null && endpoints.node(end.side) == null) {
                            endpoints.refresh();
                        }
                    }
                }
                continue;
            }
            keep.add(key);
            IGridConnection existing = connections.get(key);
            if (existing != null && alive(existing, a, b)) {
                continue;
            }
            connections.remove(key);
            if (failed.contains(key) || connected(a, b)) {
                continue;
            }
            try {
                connections.put(key, GridHelper.createConnection(a, b));
            } catch (IllegalStateException | IllegalArgumentException e) {
                failed.add(key);
                Reticulum.LOGGER.debug("ME link {} could not connect: {}", key, e.getMessage());
            }
        }
        Iterator<Map.Entry<Key, IGridConnection>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, IGridConnection> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                destroy(entry.getValue());
                it.remove();
            }
        }
    }

    @Override
    public void clear() {
        connections.values().forEach(Ae2Links::destroy);
        connections.clear();
        failed.clear();
    }

    @Override
    public Map<String, MeLinker.Usage> usage(MinecraftServer server, Network network, Collection<CompiledSheet.MeLink> wanted) {
        Map<String, MeLinker.Usage> out = new HashMap<>();
        for (CompiledSheet.MeLink link : wanted) {
            put(out, link.pinA(), endpoints(server, network, link.connectorA()), link.sideA());
            put(out, link.pinB(), endpoints(server, network, link.connectorB()), link.sideB());
        }
        return out;
    }

    private static void put(Map<String, MeLinker.Usage> out, String port, @Nullable MeEndpoints endpoints, Direction side) {
        MeLinker.Usage usage = endpoints == null ? null : endpoints.usage(side);
        if (usage != null) {
            out.put(port, usage);
        }
    }

    /** The grid node of a connector side, if the connector is loaded and has an endpoint there. */
    @Nullable
    private static IGridNode node(MinecraftServer server, Network network, End end) {
        MeEndpoints endpoints = endpoints(server, network, end.connector);
        return endpoints == null ? null : endpoints.node(end.side);
    }

    @Nullable
    private static MeEndpoints endpoints(MinecraftServer server, Network network, UUID connector) {
        ConnectorRecord record = network.connector(connector);
        if (record == null) {
            return null;
        }
        ServerLevel level = server.getLevel(record.pos().dimension());
        return level == null ? null : Ae2Integration.existing(level, record.pos().pos());
    }

    /**
     * Destroys a link still standing. One whose end was destroyed (its connector unloaded or broken) went with it:
     * destroying it again would make AE2 build a fresh grid for that dead node, which then ticks forever.
     */
    private static void destroy(IGridConnection connection) {
        if (connection.a().getConnections().contains(connection) && connection.b().getConnections().contains(connection)) {
            connection.destroy();
        }
    }

    private static boolean alive(IGridConnection connection, IGridNode a, IGridNode b) {
        return (connection.a() == a && connection.b() == b || connection.a() == b && connection.b() == a) && a.getConnections().contains(connection);
    }

    private static boolean connected(IGridNode a, IGridNode b) {
        for (IGridConnection connection : a.getConnections()) {
            if (connection.getOtherSide(a) == b) {
                return true;
            }
        }
        return false;
    }
}
