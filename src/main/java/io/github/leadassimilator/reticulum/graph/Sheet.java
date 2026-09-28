package io.github.leadassimilator.reticulum.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;

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
            Node.CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(s -> s.nodes),
            Wire.CODEC.listOf().optionalFieldOf("wires", List.of()).forGetter(s -> s.wires)
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
    /** Bumped on every edit; not saved. The engine recompiles when it changes. */
    private int revision;

    public Sheet(UUID id, String name) {
        this(id, name, "", true, DEFAULT_INTERVAL, 0, false, List.of(), List.of());
    }

    private Sheet(UUID id, String name, String folder, boolean enabled, int interval, int priority, boolean subsheet, List<Node> nodes,
            List<Wire> wires) {
        this.id = id;
        this.name = name;
        this.folder = folder;
        this.enabled = enabled;
        this.interval = interval;
        this.priority = priority;
        this.subsheet = subsheet;
        this.nodes.addAll(nodes);
        this.wires.addAll(wires);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        touch();
    }

    public String folder() {
        return folder;
    }

    /** Folder path, "/" separated; empty for the top level. */
    public void setFolder(String folder) {
        this.folder = normalizeFolder(folder);
        touch();
    }

    public static String normalizeFolder(String folder) {
        StringBuilder out = new StringBuilder();
        for (String part : folder.split("/")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.append(out.isEmpty() ? "" : "/").append(trimmed);
            }
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
