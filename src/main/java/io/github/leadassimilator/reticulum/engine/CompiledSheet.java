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
import io.github.leadassimilator.reticulum.network.Network;

/**
 * A sheet turned into routes: every path from a source through filters, rate limits, splitters and kits to a sink.
 * Compiled once per sheet revision; problems are collected in {@link #errors()} and the affected routes left out.
 */
public final class CompiledSheet {
    /** A choice made where several wires leave one output. */
    public record Branch(UUID node, int index, int count, NodeSettings.SplitMode mode) {
    }

    /** One filter on the way, and whether the route took its reject output. */
    public record Gate(Filters.ItemFilter items, Filters.FluidFilter fluids, Filters.ChemicalFilter chemicals, boolean reject) {
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
    /** Revision of every sub-sheet pasted in, so a change to one recompiles the sheets using it. */
    private final Map<UUID, Integer> dependencies = new LinkedHashMap<>();
    private final List<Source> sources = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Filters.ItemFilter> itemLimitFilters = new LinkedHashMap<>();
    private final Map<String, Filters.FluidFilter> fluidLimitFilters = new LinkedHashMap<>();
    private final Map<String, Filters.ChemicalFilter> chemicalLimitFilters = new LinkedHashMap<>();

    private CompiledSheet(Sheet sheet, int filtersHash) {
        this.sheet = sheet;
        this.revision = sheet.revision();
        this.filtersHash = filtersHash;
    }

    public static CompiledSheet compile(Sheet sheet, Network network) {
        CompiledSheet compiled = new CompiledSheet(sheet, network.filters().hashCode());
        compiled.build(network);
        return compiled;
    }

    public boolean isCurrent(Sheet sheet, Network network) {
        if (this.sheet != sheet || revision != sheet.revision() || filtersHash != network.filters().hashCode()) {
            return false;
        }
        for (Map.Entry<UUID, Integer> dependency : dependencies.entrySet()) {
            Sheet used = network.sheet(dependency.getKey());
            if (used == null || used.revision() != dependency.getValue()) {
                return false;
            }
        }
        return true;
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

    private void build(Network network) {
        Map<String, String> templates = network.filters();
        List<Node> flatNodes = new ArrayList<>();
        List<Wire> flatWires = new ArrayList<>(sheet.wires());
        paste(network, sheet.nodes(), flatNodes, flatWires, List.of(sheet.id()));

        Map<UUID, Node> nodes = new LinkedHashMap<>();
        Map<UUID, NodeSettings.DeviceSettings> devices = new LinkedHashMap<>();
        for (Node node : flatNodes) {
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
        for (Wire original : flatWires) {
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

    /**
     * The node the editor shows for a node id: a device node for its internal sources and sinks, the sub-sheet
     * node for anything pasted in from a sub-sheet, else the node itself.
     */
    public UUID owner(UUID node) {
        UUID current = node;
        for (int hops = 0; hops < 32; hops++) {
            UUID next = owners.get(current);
            if (next == null) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static final int MAX_SUBSHEET_DEPTH = 8;

    /**
     * Copies nodes into {@code out}, replacing every sub-sheet node with a copy of that sheet's nodes and wires.
     * Wires on the sub-sheet node are moved to its Sheet input and Sheet output nodes inside the copy.
     */
    private void paste(Network network, List<Node> from, List<Node> out, List<Wire> wires, List<UUID> using) {
        for (Node node : from) {
            if (!node.enabled()) {
                continue;
            }
            if (!(node.settings() instanceof NodeSettings.SubSheetSettings sub)) {
                out.add(node);
                continue;
            }
            Sheet used = network.sheet(sub.sheet());
            if (used == null || !used.subsheet()) {
                errors.add(describe(node) + ": the sub-sheet it uses no longer exists or is no longer marked as a sub-sheet");
                continue;
            }
            if (using.contains(used.id()) || using.size() > MAX_SUBSHEET_DEPTH) {
                errors.add(describe(node) + ": sub-sheet '" + used.name() + "' contains itself");
                continue;
            }
            dependencies.put(used.id(), used.revision());
            java.util.function.Function<UUID, UUID> copy = id ->
                    UUID.nameUUIDFromBytes((node.id() + "/" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<Node> copies = new ArrayList<>();
            for (Node inner : used.nodes()) {
                Node pasted = new Node(copy.apply(inner.id()), inner.settings(), inner.label(), inner.enabled(), inner.x(), inner.y());
                copies.add(pasted);
                owners.put(pasted.id(), node.id());
            }
            for (Wire inner : used.wires()) {
                wires.add(new Wire(copy.apply(inner.id()), copy.apply(inner.from()), inner.output(), copy.apply(inner.to()), inner.input(), inner.order()));
            }
            List<Node> inputs = pins(used, true);
            List<Node> outputs = pins(used, false);
            for (int i = 0; i < wires.size(); i++) {
                Wire w = wires.get(i);
                if (w.to().equals(node.id())) {
                    UUID pin = w.input() < inputs.size() ? copy.apply(inputs.get(w.input()).id()) : null;
                    wires.set(i, pin == null ? w : new Wire(w.id(), w.from(), w.output(), pin, 0, w.order()));
                } else if (w.from().equals(node.id())) {
                    UUID pin = w.output() < outputs.size() ? copy.apply(outputs.get(w.output()).id()) : null;
                    wires.set(i, pin == null ? w : new Wire(w.id(), pin, Wire.MATCH, w.to(), w.input(), w.order()));
                }
            }
            List<UUID> deeper = new ArrayList<>(using);
            deeper.add(used.id());
            paste(network, copies, out, wires, deeper);
        }
    }

    /** A sub-sheet's Sheet input (or output) nodes in pin order: top to bottom, then left to right. */
    public static List<Node> pins(Sheet sheet, boolean input) {
        List<Node> pins = new ArrayList<>();
        for (Node node : sheet.nodes()) {
            if (node.settings() instanceof NodeSettings.SheetPinSettings pin && pin.input() == input) {
                pins.add(node);
            }
        }
        pins.sort(Comparator.comparingDouble(Node::y).thenComparingDouble(Node::x).thenComparing(n -> n.id().toString()));
        return pins;
    }

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
                next.gates.add(new Gate(gate.items(), gate.fluids(), gate.chemicals(), output == Wire.REJECT));
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
            return new Gate(Filters.items(expression, templates), fluidsOrAny(expression, templates), chemicalsOrNone(expression, templates), false);
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

    /** Likewise for chemicals, which also match nothing without Mekanism. */
    private static Filters.ChemicalFilter chemicalsOrNone(String expression, Map<String, String> templates) {
        try {
            return Filters.chemicals(expression, templates);
        } catch (FilterException e) {
            return Filters.ChemicalFilter.NONE;
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
                chemicalLimitFilters.computeIfAbsent(expression, e -> chemicalsOrNone(e, templates));
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

    public Filters.ChemicalFilter chemicalFilter(NodeSettings.Limit limit) {
        return chemicalLimitFilters.getOrDefault(limit.filter(), Filters.ChemicalFilter.NONE);
    }

    static String describe(Node node) {
        return node.label().orElse(node.kind().getSerializedName() + " " + node.id().toString().substring(0, 8));
    }
}
