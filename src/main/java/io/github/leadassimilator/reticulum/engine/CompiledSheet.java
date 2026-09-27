package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;

/**
 * A sheet turned into routes: every path from a source through filters, rate limits, splitters and kits to a sink.
 * Compiled once per sheet revision; problems are collected in {@link #errors()} and the affected routes left out.
 */
public final class CompiledSheet {
    /** A choice made where several wires leave one output. */
    public record Branch(UUID node, int index, int count, NodeSettings.SplitMode mode) {
    }

    /** One filter on the way, and whether the route took its reject output. */
    public record Gate(Filters.ItemFilter items, Filters.FluidFilter fluids, boolean reject) {
    }

    /** {@code path} lists every node from source to sink and {@code wires} every wire between them, for metrics. */
    public record Route(Node source, Node sink, List<Gate> gates, List<Node> rateLimits, List<Branch> branches,
            @Nullable Node kit, List<Gate> gatesBeforeKit, List<UUID> path, List<UUID> wires) {
        public NodeSettings.SourceSettings sourceSettings() {
            return (NodeSettings.SourceSettings) source.settings();
        }

        public NodeSettings.SinkSettings sinkSettings() {
            return (NodeSettings.SinkSettings) sink.settings();
        }
    }

    /** A source and every route leaving it, in wire order. */
    public record Source(Node node, NodeSettings.SourceSettings settings, List<Route> routes) {
        public ResourceKind resource() {
            return settings.port().resource();
        }
    }

    private final Sheet sheet;
    private final int revision;
    private final int filtersHash;
    private final List<Source> sources = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Filters.ItemFilter> itemLimitFilters = new LinkedHashMap<>();
    private final Map<String, Filters.FluidFilter> fluidLimitFilters = new LinkedHashMap<>();

    private CompiledSheet(Sheet sheet, int filtersHash) {
        this.sheet = sheet;
        this.revision = sheet.revision();
        this.filtersHash = filtersHash;
    }

    public static CompiledSheet compile(Sheet sheet, Map<String, String> templates) {
        CompiledSheet compiled = new CompiledSheet(sheet, templates.hashCode());
        compiled.build(templates);
        return compiled;
    }

    public boolean isCurrent(Sheet sheet, Map<String, String> templates) {
        return this.sheet == sheet && revision == sheet.revision() && filtersHash == templates.hashCode();
    }

    public Sheet sheet() {
        return sheet;
    }

    public List<Source> sources() {
        return sources;
    }

    public List<String> errors() {
        return errors;
    }

    // ---------------------------------------------------------------- building

    private void build(Map<String, String> templates) {
        Map<UUID, Node> nodes = new LinkedHashMap<>();
        Map<UUID, NodeSettings.DeviceSettings> devices = new LinkedHashMap<>();
        for (Node node : sheet.nodes()) {
            if (!node.enabled()) {
                continue;
            }
            if (node.settings() instanceof NodeSettings.DeviceSettings device) {
                devices.put(node.id(), device);
                expand(node, device, nodes);
            } else {
                nodes.put(node.id(), node);
            }
        }
        Map<UUID, List<Wire>> outgoing = new LinkedHashMap<>();
        for (Wire original : sheet.wires()) {
            // Wires on a device node really leave one of its outputs or enter one of its inputs.
            UUID from = devices.containsKey(original.from()) ? side(original.from(), "out", original.output()) : original.from();
            UUID to = devices.containsKey(original.to()) ? side(original.to(), "in", original.input()) : original.to();
            int output = devices.containsKey(original.from()) ? Wire.MATCH : original.output();
            Wire wire = new Wire(original.id(), from, output, to, 0, original.order());
            if (nodes.containsKey(wire.from()) && nodes.containsKey(wire.to())) {
                outgoing.computeIfAbsent(wire.from(), k -> new ArrayList<>()).add(wire);
            }
        }
        outgoing.values().forEach(list -> list.sort(Comparator.comparingInt(Wire::order).thenComparing(w -> w.id().toString())));

        Map<UUID, Gate> gates = new LinkedHashMap<>();
        for (Node node : nodes.values()) {
            if (node.settings() instanceof NodeSettings.FilterSettings filter) {
                Gate gate = compileGate(node, filter.expression(), templates);
                if (gate != null) {
                    gates.put(node.id(), gate);
                }
            }
            compileLimits(node, templates);
        }

        List<Node> sourceNodes = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.kind() == NodeSettings.Kind.SOURCE) {
                sourceNodes.add(node);
            }
        }
        sourceNodes.sort(Comparator.comparingInt((Node n) -> ((NodeSettings.SourceSettings) n.settings()).priority()).reversed());
        for (Node source : sourceNodes) {
            NodeSettings.SourceSettings settings = (NodeSettings.SourceSettings) source.settings();
            List<Route> routes = new ArrayList<>();
            walk(source, source, settings.port().resource(), nodes, outgoing, gates, new Walk(), routes);
            sources.add(new Source(source, settings, routes));
        }
    }

    /** Node ids of the sources and sinks a device node is made of, mapped back to the device node (for metrics). */
    private final Map<UUID, UUID> owners = new LinkedHashMap<>();

    private static UUID side(UUID device, String direction, int index) {
        return UUID.nameUUIDFromBytes((device + ":" + direction + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void expand(Node node, NodeSettings.DeviceSettings device, Map<UUID, Node> nodes) {
        for (int i = 0; i < device.outputs().size(); i++) {
            NodeSettings.Output out = device.outputs().get(i);
            NodeSettings.Port port = new NodeSettings.Port(out.resource(), device.device(), out.faces(), out.slots());
            Node source = new Node(side(node.id(), "out", i),
                    new NodeSettings.SourceSettings(port, out.keep(), out.perOperation(), out.interval(), out.priority()),
                    node.label(), true, node.x(), node.y());
            nodes.put(source.id(), source);
            owners.put(source.id(), node.id());
        }
        for (int i = 0; i < device.inputs().size(); i++) {
            NodeSettings.Input in = device.inputs().get(i);
            NodeSettings.Port port = new NodeSettings.Port(in.resource(), device.device(), in.faces(), in.slots());
            Node sink = new Node(side(node.id(), "in", i), new NodeSettings.SinkSettings(port, in.caps()), node.label(), true, node.x(), node.y());
            nodes.put(sink.id(), sink);
            owners.put(sink.id(), node.id());
        }
    }

    /** State carried along one path of the walk. */
    private static final class Walk {
        final List<Gate> gates = new ArrayList<>();
        final List<Node> rateLimits = new ArrayList<>();
        final List<Branch> branches = new ArrayList<>();
        final Set<UUID> onPath = new HashSet<>();
        final List<UUID> path = new ArrayList<>();
        final List<UUID> wires = new ArrayList<>();
        Node kit;
        List<Gate> gatesBeforeKit = List.of();

        Walk copy() {
            Walk w = new Walk();
            w.gates.addAll(gates);
            w.rateLimits.addAll(rateLimits);
            w.branches.addAll(branches);
            w.onPath.addAll(onPath);
            w.path.addAll(path);
            w.wires.addAll(wires);
            w.kit = kit;
            w.gatesBeforeKit = gatesBeforeKit;
            return w;
        }
    }

    private void walk(Node source, Node node, ResourceKind resource, Map<UUID, Node> nodes, Map<UUID, List<Wire>> outgoing,
            Map<UUID, Gate> gates, Walk walk, List<Route> routes) {
        if (!walk.onPath.add(node.id())) {
            errors.add("Wires loop back to " + describe(node) + "; resource wires must not form a cycle");
            return;
        }
        walk.path.add(owners.getOrDefault(node.id(), node.id()));
        switch (node.settings()) {
            case NodeSettings.SinkSettings sink -> {
                if (sink.port().resource() != resource) {
                    errors.add(describe(node) + " takes " + sink.port().resource().getSerializedName()
                            + " but is wired to a " + resource.getSerializedName() + " source");
                } else {
                    routes.add(new Route(source, node, List.copyOf(walk.gates), List.copyOf(walk.rateLimits),
                            List.copyOf(walk.branches), walk.kit, walk.gatesBeforeKit, List.copyOf(walk.path), List.copyOf(walk.wires)));
                }
                return;
            }
            case NodeSettings.RateLimitSettings rate -> walk.rateLimits.add(node);
            case NodeSettings.KitSettings kit -> {
                if (resource != ResourceKind.ITEM) {
                    errors.add(describe(node) + ": kits only move items");
                    return;
                }
                if (walk.kit != null) {
                    errors.add(describe(node) + ": a route can pass through only one kit");
                    return;
                }
                walk.kit = node;
                walk.gatesBeforeKit = List.copyOf(walk.gates);
            }
            default -> {
            }
        }
        List<Wire> wires = outgoing.getOrDefault(node.id(), List.of());
        if (node.settings() instanceof NodeSettings.FilterSettings) {
            Gate gate = gates.get(node.id());
            if (gate == null) {
                return;
            }
            for (int output : new int[] {Wire.MATCH, Wire.REJECT}) {
                List<Wire> branch = wires.stream().filter(w -> w.output() == output).toList();
                Walk next = walk.copy();
                next.gates.add(new Gate(gate.items(), gate.fluids(), output == Wire.REJECT));
                follow(source, node, branch, resource, nodes, outgoing, gates, next, routes);
            }
            return;
        }
        follow(source, node, wires, resource, nodes, outgoing, gates, walk, routes);
    }

    private void follow(Node source, Node from, List<Wire> wires, ResourceKind resource, Map<UUID, Node> nodes,
            Map<UUID, List<Wire>> outgoing, Map<UUID, Gate> gates, Walk walk, List<Route> routes) {
        NodeSettings.SplitMode mode = from.settings() instanceof NodeSettings.SplitterSettings s ? s.mode() : NodeSettings.SplitMode.ROUND_ROBIN;
        for (int i = 0; i < wires.size(); i++) {
            Walk next = walk.copy();
            if (wires.size() > 1) {
                next.branches.add(new Branch(from.id(), i, wires.size(), mode));
            }
            next.wires.add(wires.get(i).id());
            walk(source, nodes.get(wires.get(i).to()), resource, nodes, outgoing, gates, next, routes);
        }
    }

    @Nullable
    private Gate compileGate(Node node, String expression, Map<String, String> templates) {
        try {
            return new Gate(Filters.items(expression, templates), fluidsOrAny(expression, templates), false);
        } catch (FilterException e) {
            errors.add(describe(node) + ": " + e.getMessage());
            return null;
        }
    }

    /** A filter valid for items may use predicates fluids don't have; such filters simply match no fluids. */
    private static Filters.FluidFilter fluidsOrAny(String expression, Map<String, String> templates) {
        try {
            return Filters.fluids(expression, templates);
        } catch (FilterException e) {
            return (stack, context) -> false;
        }
    }

    private void compileLimits(Node node, Map<String, String> templates) {
        List<NodeSettings.Limit> limits = switch (node.settings()) {
            case NodeSettings.SourceSettings s -> s.keep();
            case NodeSettings.SinkSettings s -> s.caps();
            default -> List.of();
        };
        for (NodeSettings.Limit limit : limits) {
            String expression = limit.filter();
            try {
                if (!itemLimitFilters.containsKey(expression)) {
                    itemLimitFilters.put(expression, Filters.items(expression, templates));
                }
                fluidLimitFilters.computeIfAbsent(expression, e -> fluidsOrAny(e, templates));
            } catch (FilterException e) {
                errors.add(describe(node) + ": " + e.getMessage());
            }
        }
    }

    /** The compiled filter of a keep rule or cap. Rules whose filter failed to compile match nothing. */
    public Filters.ItemFilter itemFilter(NodeSettings.Limit limit) {
        return itemLimitFilters.getOrDefault(limit.filter(), (s, c) -> false);
    }

    public Filters.FluidFilter fluidFilter(NodeSettings.Limit limit) {
        return fluidLimitFilters.getOrDefault(limit.filter(), (s, c) -> false);
    }

    static String describe(Node node) {
        return node.label().orElse(node.kind().getSerializedName() + " " + node.id().toString().substring(0, 8));
    }
}
