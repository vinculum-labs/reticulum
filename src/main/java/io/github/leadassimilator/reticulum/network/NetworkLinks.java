package io.github.leadassimilator.reticulum.network;

import java.util.ArrayDeque;
import java.util.HashSet;
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

    /** The Network node with this id on any of a network's sheets (blueprints aside), or null. */
    @Nullable
    public static Node node(Network network, UUID id) {
        for (Sheet sheet : network.sheets()) {
            if (sheet.blueprint()) {
                continue;
            }
            Node node = sheet.node(id);
            if (node != null && node.settings() instanceof NodeSettings.NetworkNodeSettings) {
                return node;
            }
        }
        return null;
    }

    /** The networks a network's Network nodes switch. */
    public static Set<UUID> switched(Network network) {
        Set<UUID> out = new HashSet<>();
        for (Sheet sheet : network.sheets()) {
            if (sheet.blueprint()) {
                continue;
            }
            for (Node node : sheet.nodes()) {
                if (node.settings() instanceof NodeSettings.NetworkNodeSettings settings) {
                    out.add(settings.network());
                }
            }
        }
        return out;
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
        NetworkManager.changed(server, target);
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
