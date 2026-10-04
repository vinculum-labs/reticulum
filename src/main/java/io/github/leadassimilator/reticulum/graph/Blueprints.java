package io.github.leadassimilator.reticulum.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;


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

    /** An instance's bindings for the slots its blueprint still has (one taken out leaves its block free). */
    public static Map<UUID, NodeSettings.Binding> liveBindings(@Nullable Sheet blueprint, NodeSettings.InstanceSettings instance) {
        Map<UUID, NodeSettings.Binding> out = new HashMap<>();
        if (blueprint != null) {
            instance.bindings().forEach((slot, binding) -> {
                if (blueprint.node(slot) != null) {
                    out.put(slot, binding);
                }
            });
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
