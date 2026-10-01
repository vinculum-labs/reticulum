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
 * A subnetwork: a canvas of nodes and wires. Every edit must go through a method here (or call {@link #touch()})
 * so the engine recompiles it.
 */
public final class Sheet {
    public static final int DEFAULT_INTERVAL = 10;

    public static final Codec<Sheet> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(s -> s.id),
            Codec.STRING.fieldOf("name").forGetter(s -> s.name),
            Codec.STRING.optionalFieldOf("folder", "").forGetter(s -> s.folder),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled),
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(s -> s.interval),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(s -> s.priority),
            Codec.BOOL.optionalFieldOf("subsheet", false).forGetter(s -> s.subsheet),
            Ids.lenientList(Node.CODEC, "node").optionalFieldOf("nodes", List.of()).forGetter(s -> s.nodes),
            Wire.CODEC.listOf().optionalFieldOf("wires", List.of()).forGetter(s -> s.wires),
            Redstone.CODEC.optionalFieldOf("redstone", Redstone.IGNORED).forGetter(s -> s.redstone)
    ).apply(i, Sheet::new));

    private final UUID id;
    private String name;
    private String folder;
    private boolean enabled;
    private int interval;
    private int priority;
    /** A sub-sheet never runs on its own; other sheets place copies of it. */
    private boolean subsheet;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private Redstone redstone;
    /** Bumped on every edit; not saved. The engine recompiles when it changes. */
    private int revision;

    public Sheet(UUID id, String name) {
        this(id, name, "", true, DEFAULT_INTERVAL, 0, false, List.of(), List.of(), Redstone.IGNORED);
    }

    private Sheet(UUID id, String name, String folder, boolean enabled, int interval, int priority, boolean subsheet, List<Node> nodes,
            List<Wire> wires, Redstone redstone) {
        this.id = id;
        this.name = name;
        this.folder = folder;
        this.enabled = enabled;
        this.interval = interval;
        this.priority = priority;
        this.subsheet = subsheet;
        this.nodes.addAll(nodes);
        this.wires.addAll(wires);
        this.redstone = redstone;
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
        Sheet copy = new Sheet(UUID.randomUUID(), name);
        copy.setFolder(folder);
        copy.setEnabled(enabled);
        copy.setInterval(interval);
        copy.priority = priority;
        copy.setSubsheet(subsheet);
        copy.setRedstone(redstone);
        java.util.Map<UUID, UUID> ids = new java.util.HashMap<>();
        for (Node node : nodes) {
            UUID id = UUID.randomUUID();
            ids.put(node.id(), id);
            copy.add(node.withId(id));
        }
        for (Wire wire : wires) {
            if (ids.containsKey(wire.from()) && ids.containsKey(wire.to())) {
                copy.connect(new Wire(UUID.randomUUID(), ids.get(wire.from()), wire.output(), ids.get(wire.to()), wire.input(), wire.order()));
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

    public String folder() {
        return folder;
    }

    /** Folder path, "/" separated; empty for the top level. */
    public void setFolder(String folder) {
        this.folder = normalizeFolder(folder);
        touch();
    }

    /** Each level trimmed and cut like a name, empty levels dropped, the whole path cut to {@link Limits#FOLDER}. */
    public static String normalizeFolder(String folder) {
        StringBuilder out = new StringBuilder();
        for (String part : folder.split("/")) {
            String level = Limits.name(part);
            if (level == null) {
                continue;
            }
            if (out.length() + level.length() + 1 > Limits.FOLDER) {
                break;
            }
            out.append(out.isEmpty() ? "" : "/").append(level);
        }
        return out.toString();
    }

    public boolean subsheet() {
        return subsheet;
    }

    public void setSubsheet(boolean subsheet) {
        this.subsheet = subsheet;
        touch();
    }

    public boolean enabled() {
        return enabled;
    }

    public Redstone redstone() {
        return redstone;
    }

    public void setRedstone(Redstone redstone) {
        this.redstone = redstone;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public int revision() {
        return revision;
    }

    public void touch() {
        revision++;
    }
}
