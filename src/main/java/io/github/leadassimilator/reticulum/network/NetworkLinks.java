package io.github.leadassimilator.reticulum.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Sheet;
import net.minecraft.server.MinecraftServer;

/**
 * Networks switching networks: a Network node on one network's sheet switches another with its gate. The switched
 * network remembers which node holds it and what it last said, so it keeps that while the other one stops.
 */
public final class NetworkLinks {
    private NetworkLinks() {
    }

    /** A network's Network nodes, on the sheets of its tree (never in blueprints). */
    private static List<Node> networkNodes(Network network) {
        List<Node> out = new ArrayList<>();
        for (Sheet sheet : network.sheets()) {
            if (!sheet.blueprint()) {
                sheet.nodes().stream().filter(n -> n.settings() instanceof NodeSettings.NetworkNodeSettings).forEach(out::add);
            }
        }
        return out;
    }

    /** The Network node with this id on one of a network's sheets, or null. */
    @Nullable
    public static Node node(Network network, UUID id) {
        return networkNodes(network).stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }

    /** The networks a network's Network nodes switch. */
    public static Set<UUID> switched(Network network) {
        Set<UUID> out = new HashSet<>();
        networkNodes(network).forEach(n -> out.add(((NodeSettings.NetworkNodeSettings) n.settings()).network()));
        return out;
    }

    /** A Network node gone, or pointed elsewhere: the network it held runs freely again. */
    public static void letGo(MinecraftServer server, Network network, Node node) {
        if (node.settings() instanceof NodeSettings.NetworkNodeSettings settings) {
            Network target = NetworkManager.network(server, settings.network());
            Switches.Held held = target == null ? null : target.switches().held();
            if (held != null && held.network().equals(network.id()) && held.node().equals(node.id())) {
                target.switches().setHeld(null);
                NetworkManager.changed(server, target);
            }
        }
    }

    /** Whether {@code from} switches {@code to}, directly or through networks it switches. */
    public static boolean reaches(MinecraftServer server, UUID from, UUID to) {
        ArrayDeque<UUID> todo = new ArrayDeque<>(Set.of(from));
        Set<UUID> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            UUID at = todo.poll();
            if (at.equals(to)) {
                return true;
            }
            Network network = NetworkManager.network(server, at);
            if (network != null && seen.add(at)) {
                todo.addAll(switched(network));
            }
        }
        return false;
    }

    /**
     * What a Network node says this tick: {@code on} for the network it names, if that network is still held by it
     * (or by nothing). Returns whether that changed anything.
     */
    public static boolean say(MinecraftServer server, Network from, Node node, boolean on) {
        if (!(node.settings() instanceof NodeSettings.NetworkNodeSettings settings)) {
            return false;
        }
        Network target = NetworkManager.network(server, settings.network());
        if (target == null || target == from) {
            return false;
        }
        Switches.Held held = target.switches().held();
        if (held != null && !(held.network().equals(from.id()) && held.node().equals(node.id()))) {
            return false;
        }
        if (held != null && held.on() == on) {
            return false;
        }
        target.switches().setHeld(new Switches.Held(from.id(), node.id(), on));
        // Saved, but no new snapshot: a clock on the gate would resend the whole network each flip. The editor gets
        // the setting with the metrics.
        NetworkManager.data(server).setDirty();
        return true;
    }

    /** A network is no longer held once the node holding it is gone (or names another network): it runs freely again. */
    public static void release(MinecraftServer server, Network network) {
        Switches.Held held = network.switches().held();
        if (held == null) {
            return;
        }
        Network from = NetworkManager.network(server, held.network());
        Node node = from == null ? null : node(from, held.node());
        if (node == null || !((NodeSettings.NetworkNodeSettings) node.settings()).network().equals(network.id())) {
            network.switches().setHeld(null);
            NetworkManager.changed(server, network);
        }
    }
}
