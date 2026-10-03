package io.github.leadassimilator.reticulum.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;
import io.github.leadassimilator.reticulum.network.Limits;

/**
 * A canvas of nodes and wires. Sheets form a tree: the network's root sheet holds the others as sheet nodes, which
 * hold theirs (a sheet's parent is wherever its sheet node is), and a sheet node's switch and gate switch everything
 * in it. Blueprints stand apart, placed by copy. Every edit must go through a method here (or call
 * {@link #touch()}) so the engine recompiles it.
 */
public final class Sheet {
    public static final int DEFAULT_INTERVAL = 10;

    public static final Codec<Sheet> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(s -> s.id),
            Codec.STRING.fieldOf("name").forGetter(s -> s.name),
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(s -> s.interval),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(s -> s.priority),
            Codec.BOOL.optionalFieldOf("blueprint", false).forGetter(s -> s.blueprint),
            Codec.BOOL.optionalFieldOf("root", false).forGetter(s -> s.root),
            Ids.lenientList(Node.CODEC, "node").optionalFieldOf("nodes", List.of()).forGetter(s -> s.nodes),
            Wire.CODEC.listOf().optionalFieldOf("wires", List.of()).forGetter(s -> s.wires)
    ).apply(i, Sheet::new));

    private final UUID id;
    private String name;
    private int interval;
    private int priority;
    /** A blueprint never runs on its own; other sheets place copies of it. */
    private boolean blueprint;
    /** The network's root sheet: the top of the tree, switched by the network itself. One per network. */
    private boolean root;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    /** Bumped on every edit; not saved. The engine recompiles when it changes. */
    private int revision;

    public Sheet(UUID id, String name) {
        this(id, name, DEFAULT_INTERVAL, 0, false, false, List.of(), List.of());
    }

    private Sheet(UUID id, String name, int interval, int priority, boolean blueprint, boolean root, List<Node> nodes, List<Wire> wires) {
        this.id = id;
        this.name = name;
        this.interval = interval;
        this.priority = priority;
        this.blueprint = blueprint;
        this.root = root;
        this.nodes.addAll(nodes);
        // Wires whose ends are gone (nodes of a kind that no longer exists are left out on load) go with them.
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        nodes.forEach(n -> ids.add(n.id()));
        wires.stream().filter(w -> ids.contains(w.from()) && ids.contains(w.to())).forEach(this.wires::add);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /**
     * A copy of a sheet (e.g. pasted from another world) with new ids for itself, its nodes and its wires, so it can
     * sit beside the original. Device references stay as they are.
     */
    public Sheet copyWithNewIds(String name) {
        return copyWithNewIds(name, new java.util.HashMap<>());
    }

    /** As {@link #copyWithNewIds(String)}, filling {@code ids} with each node's new id by its old one. */
    public Sheet copyWithNewIds(String name, java.util.Map<UUID, UUID> ids) {
        Sheet copy = new Sheet(UUID.randomUUID(), name);
        copy.setInterval(interval);
        copy.priority = priority;
        copy.setBlueprint(blueprint);
        for (Node node : nodes) {
            UUID id = UUID.randomUUID();
            ids.put(node.id(), id);
            copy.add(node.withId(id));
        }
        java.util.Map<UUID, UUID> wireIds = new java.util.HashMap<>();
        for (Wire wire : wires) {
            if (ids.containsKey(wire.from()) && ids.containsKey(wire.to())) {
                UUID id = UUID.randomUUID();
                wireIds.put(wire.id(), id);
                copy.connect(new Wire(id, ids.get(wire.from()), wire.output(), ids.get(wire.to()), wire.input(), wire.order()));
            }
        }
        // A splitter's weights are by wire: they follow the wires to their new ids.
        for (Node node : List.copyOf(copy.nodes)) {
            if (node.settings() instanceof NodeSettings.SplitterSettings splitter && !splitter.weights().isEmpty()) {
                copy.replace(node.with(splitter.renamed(id -> wireIds.getOrDefault(id, id))));
            }
        }
        return copy;
    }

    /** Trimmed and cut to {@link Limits#NAME}; a blank name leaves the old one. */
    public void setName(String name) {
        String value = Limits.name(name);
        if (value != null) {
            this.name = value;
            touch();
        }
    }

    public boolean blueprint() {
        return blueprint;
    }

    public boolean root() {
        return root;
    }

    public void setRoot(boolean root) {
        this.root = root;
        touch();
    }

    public void setBlueprint(boolean blueprint) {
        this.blueprint = blueprint;
        touch();
    }

    public int interval() {
        return Math.max(1, interval);
    }

    public void setInterval(int interval) {
        this.interval = interval;
        touch();
    }

    public int priority() {
        return priority;
    }

    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    public List<Wire> wires() {
        return List.copyOf(wires);
    }

    @Nullable
    public Node node(UUID id) {
        for (Node node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    public Node add(Node node) {
        nodes.add(node);
        touch();
        return node;
    }

    public void replace(Node node) {
        nodes.replaceAll(n -> n.id().equals(node.id()) ? node : n);
        touch();
    }

    public void remove(UUID nodeId) {
        nodes.removeIf(n -> n.id().equals(nodeId));
        wires.removeIf(w -> w.from().equals(nodeId) || w.to().equals(nodeId));
        touch();
    }

    public Wire connect(Wire wire) {
        wires.add(wire);
        touch();
        return wire;
    }

    /** Puts these wires (a splitter's branches, say) in this order; the rest keep theirs. */
    public void orderWires(List<UUID> ids) {
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            int order = i;
            wires.replaceAll(w -> w.id().equals(id) ? new Wire(w.id(), w.from(), w.output(), w.to(), w.input(), order) : w);
        }
        touch();
    }

    public void disconnect(UUID wireId) {
        wires.removeIf(w -> w.id().equals(wireId));
        touch();
    }

    /**
     * Gives every Sheet input and Sheet output a slot of its own, numbered per direction: pins without one (new, or
     * from an older save) get the next free number, in the order they are laid out (top to bottom, then left to
     * right), which is the order older wires counted them in. Returns whether anything changed.
     */
    public boolean assignPinSlots() {
        boolean changed = false;
        for (boolean input : new boolean[] {true, false}) {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            List<Node> unslotted = new ArrayList<>();
            for (Node node : pins(input)) {
                int slot = ((NodeSettings.SheetPinSettings) node.settings()).slot();
                if (slot >= 0 && used.add(slot)) {
                    continue;
                }
                unslotted.add(node);
            }
            int next = 0;
            for (Node node : unslotted) {
                while (used.contains(next)) {
                    next++;
                }
                used.add(next);
                replace(node.with(((NodeSettings.SheetPinSettings) node.settings()).withSlot(next)));
                changed = true;
            }
        }
        return changed;
    }

    /** This sheet's Sheet input (or output) nodes in the order they are laid out: top to bottom, then left to right. */
    public List<Node> pins(boolean input) {
        List<Node> pins = new ArrayList<>();
        for (Node node : nodes) {
            if (node.settings() instanceof NodeSettings.SheetPinSettings pin && pin.input() == input) {
                pins.add(node);
            }
        }
        pins.sort(java.util.Comparator.comparingDouble(Node::y).thenComparingDouble(Node::x).thenComparing(n -> n.id().toString()));
        return pins;
    }

    /** The Sheet input (or output) node with this slot, or null. */
    @Nullable
    public Node pin(boolean input, int slot) {
        for (Node node : nodes) {
            if (node.settings() instanceof NodeSettings.SheetPinSettings pin && pin.input() == input && pin.slot() == slot) {
                return node;
            }
        }
        return null;
    }

    public int revision() {
        return revision;
    }

    public void touch() {
        revision++;
    }

    /** This sheet's contents as they are now (nodes and wires are immutable), to put back with {@link #restore}. */
    public Sheet saved() {
        return new Sheet(id, name, interval, priority, blueprint, root, nodes, wires);
    }

    /** Puts back what {@link #saved} kept, as a change (the engine recompiles it). */
    public void restore(Sheet saved) {
        name = saved.name;
        interval = saved.interval;
        priority = saved.priority;
        blueprint = saved.blueprint;
        root = saved.root;
        nodes.clear();
        nodes.addAll(saved.nodes);
        wires.clear();
        wires.addAll(saved.wires);
        touch();
    }
}
