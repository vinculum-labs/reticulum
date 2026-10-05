package io.github.leadassimilator.reticulum.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.network.Network;


/**
 * Blueprints: reusable sheets whose device nodes are slots, placeholders for a kind of block. An instance of one (an instance
 * node) says which block fills each slot; this is how its nodes read with those blocks in place.
 */
public final class Blueprints {
    private Blueprints() {
    }

    /**
     * A blueprint node as an instance with these bindings has it: a slot gets its block (faces turned to match), a Level
     * node reading a slot reads the block filling it. Null for a slot nothing fills, or a sensor reading one: it can't run.
     */
    @Nullable
    public static NodeSettings bound(Node inner, Map<UUID, NodeSettings.Binding> bindings) {
        return switch (inner.settings()) {
            case NodeSettings.DeviceSettings device -> {
                NodeSettings.Binding binding = bindings.get(inner.id());
                yield binding == null ? null : device.boundTo(binding);
            }
            case NodeSettings.LevelSettings level when level.slot().isPresent() -> {
                NodeSettings.Binding binding = bindings.get(level.slot().get());
                yield binding == null ? null : level.readingDevice(binding.device(), NodeSettings.Binding.turn(level.faces(), binding.turns()));
            }
            default -> inner.settings();
        };
    }

    /** The slot a blueprint node needs filled to run: itself for a slot, the slot it reads for a Level node. */
    @Nullable
    public static UUID slotNeeded(Node inner) {
        return switch (inner.settings()) {
            case NodeSettings.DeviceSettings device -> inner.id();
            case NodeSettings.LevelSettings level -> level.slot().orElse(null);
            default -> null;
        };
    }

    /** How deep blueprints placed in blueprints are followed (the engine refuses deeper ones). */
    private static final int NESTING = 8;

    /**
     * The key a slot of a blueprint placed in another has in a placement of the outer one: made from the inner placed
     * blueprint's node and the slot's key in that one, as the engine names the nodes it pastes.
     */
    public static UUID nested(UUID placed, UUID key) {
        return UUID.nameUUIDFromBytes((placed + "/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Every slot a placement of this blueprint fills, by the key its bindings use: its own slots by id, and those of
     * blueprints placed in it (any depth) by {@link #nested} keys. Each placement fills them all with blocks of its own.
     */
    public static Map<UUID, Node> slots(Network network, @Nullable Sheet blueprint) {
        Map<UUID, Node> out = new java.util.LinkedHashMap<>();
        if (blueprint != null) {
            collect(network, blueprint, key -> key, out, 0, new java.util.HashSet<>());
        }
        return out;
    }

    private static void collect(Network network, Sheet blueprint, java.util.function.UnaryOperator<UUID> key, Map<UUID, Node> out, int depth,
            java.util.Set<UUID> path) {
        if (depth >= NESTING || !path.add(blueprint.id())) {
            return;
        }
        for (Node node : blueprint.nodes()) {
            if (node.settings() instanceof NodeSettings.DeviceSettings) {
                out.put(key.apply(node.id()), node);
            } else if (node.settings() instanceof NodeSettings.InstanceSettings placed && network.sheet(placed.sheet()) instanceof Sheet inner
                    && inner.blueprint()) {
                UUID at = node.id();
                collect(network, inner, inside -> key.apply(nested(at, inside)), out, depth + 1, path);
            }
        }
        path.remove(blueprint.id());
    }

    /** An instance's bindings for the slots it still has, its blueprints' placed in it included (one taken out leaves its block free). */
    public static Map<UUID, NodeSettings.Binding> liveBindings(Network network, @Nullable Sheet blueprint, NodeSettings.InstanceSettings instance) {
        Map<UUID, NodeSettings.Binding> out = new HashMap<>();
        if (blueprint != null) {
            java.util.Set<UUID> keys = slots(network, blueprint).keySet();
            instance.bindings().forEach((slot, binding) -> {
                if (keys.contains(slot)) {
                    out.put(slot, binding);
                }
            });
        }
        return out;
    }

    /**
     * The bindings a blueprint placed at {@code placed} inside another gets from a placement of the outer one: the outer
     * placement's blocks for the inner one's slots, by the inner one's own keys.
     */
    public static Map<UUID, NodeSettings.Binding> inner(Network network, Map<UUID, NodeSettings.Binding> outer, UUID placed, @Nullable Sheet innerBlueprint) {
        Map<UUID, NodeSettings.Binding> out = new HashMap<>();
        for (UUID key : slots(network, innerBlueprint).keySet()) {
            NodeSettings.Binding binding = outer.get(nested(placed, key));
            if (binding != null) {
                out.put(key, binding);
            }
        }
        return out;
    }

    /** How a slot is named in messages: its label, else its kind of block. */
    public static String slotName(Node slot) {
        if (slot.label().isPresent()) {
            return "'" + slot.label().get() + "'";
        }
        if (slot.settings() instanceof NodeSettings.DeviceSettings device && device.block().isPresent()) {
            return "for " + device.block().get().getPath().replace('_', ' ');
        }
        return "at " + (slot.settings() instanceof NodeSettings.DeviceSettings device ? device.device().pos().toShortString() : "?");
    }
}
