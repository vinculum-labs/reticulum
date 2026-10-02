package io.github.leadassimilator.reticulum.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.GlobalPos;

/**
 * Blueprints: reusable sheets whose device nodes are slots, placeholders for a kind of block. A copy of one (a copy
 * node) says which block fills each slot; this is how its nodes read with those blocks in place.
 */
public final class Blueprints {
    private Blueprints() {
    }

    /**
     * A blueprint node as a copy with these bindings has it: a slot gets its block (faces turned to match), a Level
     * node reading a slot's block reads the block filling it. Null for a slot nothing fills: it can't run.
     */
    @Nullable
    public static NodeSettings bound(Node inner, Map<UUID, NodeSettings.Binding> bindings, Map<GlobalPos, NodeSettings.Binding> byLayout) {
        return switch (inner.settings()) {
            case NodeSettings.DeviceSettings device -> {
                NodeSettings.Binding binding = bindings.get(inner.id());
                yield binding == null ? null : device.boundTo(binding);
            }
            case NodeSettings.LevelSettings level -> {
                NodeSettings.Binding binding = byLayout.get(level.device());
                yield binding == null ? level : level.reading(binding.device(), NodeSettings.Binding.turn(level.faces(), binding.turns()));
            }
            default -> inner.settings();
        };
    }

    /** Each bound slot's binding by the block position the slot was laid out at (what Level nodes in it name). */
    public static Map<GlobalPos, NodeSettings.Binding> byLayout(Sheet blueprint, Map<UUID, NodeSettings.Binding> bindings) {
        Map<GlobalPos, NodeSettings.Binding> out = new HashMap<>();
        if (bindings.isEmpty()) {
            return out;
        }
        for (Node node : blueprint.nodes()) {
            if (node.settings() instanceof NodeSettings.DeviceSettings device && bindings.containsKey(node.id())) {
                out.put(device.device(), bindings.get(node.id()));
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
