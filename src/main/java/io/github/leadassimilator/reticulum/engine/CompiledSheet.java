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
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;

/**
 * A network's sheets turned into routes: every path from a source through filters, rate limits, splitters and kits to
 * a sink. The whole tree is compiled at once, from the root sheet down: each child sheet's nodes and wires are pasted
 * in where its sheet node is (under their own ids), so routes run across sheets through their pins, and each blueprint
 * is pasted as a copy. Compiled again whenever a sheet in it changes; problems are collected in {@link #errors()} and
 * the affected routes left out.
 */
public final class CompiledSheet {
    /** A choice made where several wires leave one output. */
    /** {@code weights}: each output's weight, for WEIGHTED splitters (all 1 otherwise). */
    public record Branch(UUID node, int index, int count, NodeSettings.SplitMode mode, int[] weights) {
        public int weight() {
            return weights[index];
        }

        public int totalWeight() {
            int total = 0;
            for (int w : weights) {
                total += w;
            }
            return total;
        }
    }

    /** One filter on the way, and whether the route took its reject output. */
    public record Gate(Filters.ItemFilter items, Filters.FluidFilter fluids, Filters.ChemicalFilter chemicals, boolean reject) {
    }

    /**
     * {@code path} lists every node from source to sink and {@code wires} every wire between them, for metrics.
     * {@code controls} holds every node whose switch or gate can switch the route off: the nodes on it,
     * the device nodes their sources and sinks belong to, and the copy nodes they were pasted from.
     */
    public record Route(Node source, Node sink, List<Gate> gates, List<Node> rateLimits, List<Branch> branches,
            @Nullable Node kit, List<Gate> gatesBeforeKit, List<UUID> path, List<UUID> wires, Set<UUID> controls) {
        public NodeSettings.SourceSettings sourceSettings() {
            return (NodeSettings.SourceSettings) source.settings();
        }

        public NodeSettings.SinkSettings sinkSettings() {
            return (NodeSettings.SinkSettings) sink.settings();
        }
    }

    /** A source and every route leaving it, in wire order. {@code controls} as for a route. */
    public record Source(Node node, NodeSettings.SourceSettings settings, List<Route> routes, Set<UUID> controls) {
        public ResourceKind resource() {
            return settings.port().resource();
        }
    }

    private final Sheet sheet;
    private final int revision;
    private final int filtersHash;
    /** Network revision when compiled; ME pins resolve to connectors, so sheets with them follow connector changes. */
    private final int networkRevision;
    private boolean followsConnectors;
    /** Revision of every sheet and blueprint pasted in, so a change to one compiles the network again. */
    private final Map<UUID, Integer> dependencies = new LinkedHashMap<>();
    /** For every node as compiled (pasted copies, device sides and pins included): the sheet it shows on. */
    private final Map<UUID, UUID> nodeSheet = new LinkedHashMap<>();
    /** Each sheet's operation interval and priority, by sheet id, for what is compiled from it. */
    private final Map<UUID, int[]> sheetTiming = new LinkedHashMap<>();
    /** The sheet node of every sheet in the tree, by sheet id (the root has none). */
    private final Map<UUID, UUID> sheetNodes = new LinkedHashMap<>();
    private final List<Source> sources = new ArrayList<>();
    private final List<Problem> errors = new ArrayList<>();
    private final Map<String, Filters.ItemFilter> itemLimitFilters = new LinkedHashMap<>();
    private final Map<String, Filters.FluidFilter> fluidLimitFilters = new LinkedHashMap<>();
    private final Map<String, Filters.ChemicalFilter> chemicalLimitFilters = new LinkedHashMap<>();

    private CompiledSheet(Sheet sheet, int filtersHash, int networkRevision) {
        this.networkRevision = networkRevision;
        this.sheet = sheet;
        this.revision = sheet.revision();
        this.filtersHash = filtersHash;
    }

    /** Compiles the network from {@code sheet} down: its root sheet to run it, a blueprint on its own to check it. */
    public static CompiledSheet compile(Sheet sheet, Network network) {
        CompiledSheet compiled = new CompiledSheet(sheet, network.filters().hashCode(), network.revision());
        compiled.build(network);
        return compiled;
    }

    public boolean isCurrent(Sheet sheet, Network network) {
        if (this.sheet != sheet || revision != sheet.revision() || filtersHash != network.filters().hashCode()) {
            return false;
        }
        if (followsConnectors && networkRevision != network.revision()) {
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

    public List<Problem> errors() {
        return errors;
    }

    /** The problems about one sheet: those about its nodes, and (for the sheet compiled from) those about no node. */
    public List<Problem> errorsFor(UUID sheetId) {
        List<Problem> out = new ArrayList<>();
        for (Problem problem : errors) {
            UUID on = problem.node() == null ? sheet.id() : sheetOf(problem.node());
            if (sheetId.equals(on)) {
                out.add(problem);
            }
        }
        return out;
    }

    /** The sheet a compiled node shows on (its own, or the one holding the blueprint or device it came from). */
    public UUID sheetOf(UUID node) {
        UUID current = node;
        for (int hops = 0; hops < 32; hops++) {
            UUID on = nodeSheet.get(current);
            if (on != null) {
                return on;
            }
            UUID next = owners.get(current);
            if (next == null) {
                break;
            }
            current = next;
        }
        return sheet.id();
    }

    /** Ticks between item operations for what is compiled from this node's sheet. */
    public int intervalOf(UUID node) {
        int[] timing = sheetTiming.get(sheetOf(node));
        return timing == null ? sheet.interval() : timing[0];
    }

    /** The priority of this node's sheet: sheets with a higher one move first. */
    public int priorityOf(UUID node) {
        int[] timing = sheetTiming.get(sheetOf(node));
        return timing == null ? sheet.priority() : timing[1];
    }

    /** The sheet node of each sheet in the tree, by sheet id. */
    public Map<UUID, UUID> sheetNodes() {
        return sheetNodes;
    }

    /** The sheet node holding a node pasted in from a child sheet (a sheet node for one inside another), or null. */
    @Nullable
    public UUID containerOf(UUID node) {
        return containers.get(node);
    }

    /**
     * Something that keeps part of the sheet from running. {@code node} is the node it is about, as compiled (a device's
     * side or a node pasted from a blueprint); {@link #owner} maps it to the node the editor shows.
     */
    /**
     * A problem, about a node when {@code node} is set. {@code detail} is the message without the node's name in front
     * (when it starts with it), for the editor to show the node itself there instead.
     */
    public record Problem(String message, @Nullable UUID node, @Nullable String detail) {
    }

    private void error(@Nullable Node node, String message) {
        String named = node == null ? null : describe(node) + ": ";
        String detail = named != null && message.startsWith(named) ? message.substring(named.length()) : null;
        errors.add(new Problem(message, node == null ? null : node.id(), detail));
    }

    // ---------------------------------------------------------------- building

    /** The network being compiled, for naming its devices in problems. */
    @Nullable
    private Network network;

    private void build(Network network) {
        this.network = network;
        Map<String, String> templates = network.filters();
        List<Node> flatNodes = new ArrayList<>();
        List<Wire> flatWires = new ArrayList<>(sheet.wires());
        sheetTiming.put(sheet.id(), new int[] {sheet.interval(), sheet.priority()});
        paste(network, sheet.nodes(), flatNodes, flatWires, List.of(sheet.id()), sheet.id());
        if (tooBig) {
            return;
        }
        pins(network, flatNodes, flatWires);

        Map<UUID, Node> nodes = new LinkedHashMap<>();
        Map<UUID, NodeSettings.DeviceSettings> devices = new LinkedHashMap<>();
        Set<UUID> carriers = compileSignals(flatNodes, flatWires);
        Set<UUID> mePorts = compileMeLinks(flatNodes, flatWires);
        Map<GlobalPos, Node> homes = new LinkedHashMap<>();
        for (Node node : flatNodes) {
            if (!node.enabled()) {
                continue;
            }
            // One node per device (a slot a copy fills counts): a second one is flagged, though it still runs. An empty
            // slot of a blueprint only names a kind of block: two slots made from the same block are fine.
            if (node.settings() instanceof NodeSettings.DeviceSettings device && device.block().isEmpty()) {
                Node first = homes.putIfAbsent(device.device(), node);
                if (first != null) {
                    Sheet home = network.sheet(sheetOf(first.id()));
                    error(node, describe(node) + ": this device already has a node" + (home == null ? "" : " in " + home.name())
                            + " — a device belongs on one sheet");
                }
            }
            if (node.gate() == Node.Gate.PULSE) {
                pulseGates.add(node.id());
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
            if (carriers.contains(original.from()) || original.input() == Wire.GATE
                    || mePorts.contains(original.from()) || mePorts.contains(original.to())) {
                continue;
            }
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
        sourceNodes.sort(Comparator.comparingInt((Node n) -> priorityOf(n.id()))
                .thenComparingInt(n -> ((NodeSettings.SourceSettings) n.settings()).priority()).reversed());
        for (Node source : sourceNodes) {
            NodeSettings.SourceSettings settings = (NodeSettings.SourceSettings) source.settings();
            List<Route> routes = new ArrayList<>();
            walk(source, source, settings.port().resource(), nodes, outgoing, gates, new Walk(), routes);
            sources.add(new Source(source, settings, routes, controls(List.of(source.id()))));
        }
        if (tooBig) {
            // Nothing runs rather than whichever routes were found before giving up.
            sources.clear();
        }
    }

    // ---------------------------------------------------------------- redstone and ME pins

    /** Pin nodes made from device pins, and the pin each stands for ("node/out/0"), so the editor can show their values. */
    private final Map<UUID, String> pinKeys = new LinkedHashMap<>();

    public Map<UUID, String> pinKeys() {
        return pinKeys;
    }

    /**
     * Turns device nodes' redstone and ME pins into the nodes the engine works with (a redstone output reads the
     * device, a redstone input powers it, an ME pin is the connector side touching that face) and moves their wires
     * onto them. Item, fluid, energy and chemical pins become sources and sinks later, in {@link #expand}.
     */
    private void pins(Network network, List<Node> flat, List<Wire> wires) {
        Map<String, UUID> outs = new LinkedHashMap<>();
        Map<String, UUID> ins = new LinkedHashMap<>();
        List<Node> made = new ArrayList<>();
        for (Node node : flat) {
            if (!node.enabled() || !(node.settings() instanceof NodeSettings.DeviceSettings device)) {
                continue;
            }
            for (int i = 0; i < device.outputs().size(); i++) {
                NodeSettings.Output out = device.outputs().get(i);
                NodeSettings settings = switch (out.resource()) {
                    case REDSTONE -> new NodeSettings.RedstoneInSettings(device.device(), out.faces(), out.comparator());
                    case ME -> mePort(network, node, device.device(), out.faces(), "output " + (i + 1));
                    default -> null;
                };
                if (settings != null) {
                    made.add(pin(node, side(node.id(), "out", i), settings, node.id() + "/out/" + i));
                    outs.put(node.id() + ":" + i, side(node.id(), "out", i));
                }
            }
            for (int i = 0; i < device.inputs().size(); i++) {
                NodeSettings.Input in = device.inputs().get(i);
                NodeSettings settings = switch (in.resource()) {
                    case REDSTONE -> new NodeSettings.RedstoneOutSettings(device.device(), in.faces(), in.whileOff());
                    case ME -> mePort(network, node, device.device(), in.faces(), "input " + (i + 1));
                    default -> null;
                };
                if (settings != null) {
                    made.add(pin(node, side(node.id(), "in", i), settings, node.id() + "/in/" + i));
                    ins.put(node.id() + ":" + i, side(node.id(), "in", i));
                }
            }
        }
        if (made.isEmpty()) {
            return;
        }
        flat.addAll(made);
        for (int i = 0; i < wires.size(); i++) {
            Wire w = wires.get(i);
            UUID from = outs.get(w.from() + ":" + w.output());
            UUID to = w.input() == Wire.GATE ? null : ins.get(w.to() + ":" + w.input());
            if (from != null || to != null) {
                wires.set(i, new Wire(w.id(), from != null ? from : w.from(), from != null ? Wire.MATCH : w.output(),
                        to != null ? to : w.to(), to != null ? 0 : w.input(), w.order()));
            }
        }
    }

    private Node pin(Node device, UUID id, NodeSettings settings, String key) {
        owners.put(id, device.id());
        pinKeys.put(id, key);
        return new Node(id, settings, device.label(), true, device.x(), device.y(), Node.Gate.WHILE_ON);
    }

    /** The connector side touching one of the given faces of the device (any face when none are chosen). */
    @Nullable
    private NodeSettings.MePortSettings mePort(Network network, Node node, GlobalPos device, List<FaceKey> faces, String which) {
        followsConnectors = true;
        for (io.github.leadassimilator.reticulum.network.ConnectorRecord connector : network.connectors()) {
            if (!connector.devices().contains(device) || !connector.pos().dimension().equals(device.dimension())) {
                continue;
            }
            Direction toConnector = io.github.leadassimilator.reticulum.network.Devices.facing(device.pos(), connector.pos().pos());
            if (toConnector != null && (faces.isEmpty() || faces.contains(FaceKey.of(toConnector)))) {
                return new NodeSettings.MePortSettings(connector.id(), toConnector.getOpposite());
            }
        }
        error(node, describe(node) + ": " + which + " (ME) has no connector on " + (faces.isEmpty() ? "any face" : "that face"));
        return null;
    }

    // ---------------------------------------------------------------- ME links

    /** An ME link wire between two ME pins (connector sides); {@code pinA}/{@code pinB} name the pins ("node/out/0"). */
    public record MeLink(String pinA, UUID connectorA, Direction sideA, String pinB, UUID connectorB, Direction sideB) {
    }

    private final List<MeLink> meLinks = new ArrayList<>();

    public List<MeLink> meLinks() {
        return meLinks;
    }

    /** Takes out the wires between ME ports; they join AE2 networks and carry no resources. */
    private Set<UUID> compileMeLinks(List<Node> flatNodes, List<Wire> wires) {
        Map<UUID, NodeSettings.MePortSettings> ports = new LinkedHashMap<>();
        Map<UUID, Node> byId = new LinkedHashMap<>();
        for (Node node : flatNodes) {
            byId.put(node.id(), node);
            if (node.enabled() && node.settings() instanceof NodeSettings.MePortSettings port) {
                ports.put(node.id(), port);
            }
        }
        if (ports.isEmpty()) {
            return Set.of();
        }
        for (Wire wire : wires) {
            NodeSettings.MePortSettings a = ports.get(wire.from());
            NodeSettings.MePortSettings b = ports.get(wire.to());
            if (a != null && b != null) {
                meLinks.add(new MeLink(pinKeys.getOrDefault(wire.from(), wire.from().toString()), a.connector(), a.side(),
                        pinKeys.getOrDefault(wire.to(), wire.to().toString()), b.connector(), b.side()));
            } else if ((a != null || b != null) && wire.input() != Wire.GATE) {
                Node other = byId.get(a != null ? wire.to() : wire.from());
                error(other, (other == null ? "A node" : describe(other)) + ": ME pins only link to other ME pins");
            }
        }
        return ports.keySet();
    }

    // ---------------------------------------------------------------- signals and redstone

    /** A signal node and, for each of its inputs, the nodes wired into it. */
    public record SignalNode(Node node, List<List<UUID>> inputs) {
        public List<UUID> input(int index) {
            return index < inputs.size() ? inputs.get(index) : List.of();
        }
    }

    /** Signal nodes in the order they are worked out each tick; empty when the signal wires loop. */
    private final List<SignalNode> signals = new ArrayList<>();
    /** Nodes with a signal wire into their gate, and the nodes wired into it. */
    private final Map<UUID, List<UUID>> switchedBy = new LinkedHashMap<>();
    /** Nodes (by compiled id, including copy nodes) whose gate runs them once per pulse. */
    private final Set<UUID> pulseGates = new java.util.HashSet<>();

    public List<SignalNode> signals() {
        return signals;
    }

    public Map<UUID, List<UUID>> switchedBy() {
        return switchedBy;
    }

    /** Whether a node's gate runs it once per pulse (rather than while on). */
    public boolean pulses(UUID node) {
        return pulseGates.contains(node);
    }

    /** Whether anything on the sheet can be switched off, so the engine can skip the checks otherwise. */
    public boolean switchable() {
        return !switchedBy.isEmpty() || !disabledHolders.isEmpty();
    }

    /** Latches, counters and delays give what they remembered, so wires into them never make a loop. */
    private static boolean remembers(NodeSettings.Kind kind) {
        return kind == NodeSettings.Kind.LATCH || kind == NodeSettings.Kind.COUNTER || kind == NodeSettings.Kind.DELAY;
    }

    /**
     * Sorts out the signal side of the sheet: which nodes carry signals (signal nodes, and sheet pins fed by them),
     * what is wired into each, and which nodes are gated. Returns the carriers, whose wires the resource walk skips.
     */
    private Set<UUID> compileSignals(List<Node> flatNodes, List<Wire> wires) {
        Map<UUID, Node> byId = new LinkedHashMap<>();
        for (Node node : flatNodes) {
            if (node.enabled()) {
                byId.put(node.id(), node);
            }
        }
        Set<UUID> carriers = new java.util.LinkedHashSet<>();
        byId.values().stream().filter(n -> n.kind().signal()).forEach(n -> carriers.add(n.id()));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Wire wire : wires) {
                Node to = byId.get(wire.to());
                if (carriers.contains(wire.from()) && to != null && to.settings() instanceof NodeSettings.SheetPinSettings && carriers.add(to.id())) {
                    grew = true;
                }
            }
        }
        if (carriers.isEmpty()) {
            for (Wire wire : wires) {
                if (wire.input() == Wire.GATE) {
                    error(byId.get(wire.to()), "Only signal wires can switch a node on and off");
                }
            }
            return carriers;
        }
        Map<UUID, List<List<UUID>>> inputs = new LinkedHashMap<>();
        for (Wire wire : wires) {
            if (!carriers.contains(wire.from())) {
                if (wire.input() == Wire.GATE) {
                    error(byId.get(wire.to()), "Only signal wires can switch a node on and off");
                }
                continue;
            }
            if (!byId.containsKey(wire.from())) {
                continue;
            }
            if (wire.input() == Wire.GATE) {
                switchedBy.computeIfAbsent(wire.to(), k -> new ArrayList<>()).add(wire.from());
            } else if (carriers.contains(wire.to())) {
                List<List<UUID>> in = inputs.computeIfAbsent(wire.to(), k -> new ArrayList<>());
                while (in.size() <= wire.input()) {
                    in.add(new ArrayList<>());
                }
                in.get(wire.input()).add(wire.from());
            } else if (byId.containsKey(wire.to())) {
                error(byId.get(wire.to()), describe(byId.get(wire.to())) + ": a signal wire must go into its gate (the diamond pin), not a resource input");
            }
        }
        // Remembering nodes first, then the rest so every node comes after the nodes wired into it.
        List<SignalNode> ordered = new ArrayList<>();
        Map<UUID, Integer> waiting = new LinkedHashMap<>();
        Map<UUID, List<UUID>> feeds = new LinkedHashMap<>();
        for (UUID id : carriers) {
            Node node = byId.get(id);
            List<List<UUID>> in = inputs.getOrDefault(id, List.of());
            if (remembers(node.kind())) {
                ordered.add(new SignalNode(node, in));
                continue;
            }
            int count = 0;
            for (List<UUID> from : in) {
                for (UUID source : from) {
                    if (!remembers(byId.get(source).kind())) {
                        count++;
                        feeds.computeIfAbsent(source, k -> new ArrayList<>()).add(id);
                    }
                }
            }
            waiting.put(id, count);
        }
        java.util.ArrayDeque<UUID> ready = new java.util.ArrayDeque<>();
        waiting.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        while (!ready.isEmpty()) {
            UUID id = ready.poll();
            ordered.add(new SignalNode(byId.get(id), inputs.getOrDefault(id, List.of())));
            for (UUID next : feeds.getOrDefault(id, List.of())) {
                if (waiting.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        // What each signal output does while its sheet is off; those set to keep running keep their feeders working.
        Map<UUID, SignalNode> byNode = new LinkedHashMap<>();
        ordered.forEach(n -> byNode.put(n.node().id(), n));
        for (SignalNode n : ordered) {
            NodeSettings.OffBehavior behavior = switch (n.node().settings()) {
                case NodeSettings.SheetPinSettings pin when !pin.input() -> pin.whileOff();
                case NodeSettings.RedstoneOutSettings out -> out.whileOff();
                default -> null;
            };
            if (behavior == null) {
                continue;
            }
            whileOff.put(n.node().id(), behavior);
            if (behavior.mode() == NodeSettings.WhileOff.RUN) {
                java.util.ArrayDeque<UUID> todo = new java.util.ArrayDeque<>(List.of(n.node().id()));
                while (!todo.isEmpty()) {
                    SignalNode at = byNode.get(todo.poll());
                    if (at == null || !keepRunning.add(at.node().id())) {
                        continue;
                    }
                    at.inputs().forEach(todo::addAll);
                }
            }
        }
        if (ordered.size() < carriers.size()) {
            Set<UUID> placed = new HashSet<>();
            ordered.forEach(n -> placed.add(n.node().id()));
            UUID looped = carriers.stream().filter(id -> !placed.contains(id)).findFirst().orElse(null);
            error(looped == null ? null : byId.get(looped), "Signal wires loop back on themselves — a loop must pass through a Latch, Counter or Delay");
        } else {
            signals.addAll(ordered);
        }
        return carriers;
    }

    /**
     * The given nodes plus everything that holds them: the device nodes of device sides, the copy nodes of pasted
     * copies, and the sheet nodes of the sheets they are on, all the way up, so switching any of those off stops them.
     */
    private Set<UUID> controls(List<UUID> raw) {
        Set<UUID> out = new java.util.HashSet<>();
        for (UUID id : raw) {
            UUID current = id;
            for (int hops = 0; current != null && hops < 256; hops++) {
                out.add(current);
                current = holder(current);
            }
        }
        return out;
    }

    /** What holds a compiled node: its device or copy node, else the sheet node of the sheet it was pasted from. */
    @Nullable
    private UUID holder(UUID node) {
        UUID owner = owners.get(node);
        return owner != null ? owner : containers.get(node);
    }

    /** For every node pasted in from a child sheet: that sheet's sheet node. Not an owner: the node shows as itself. */
    private final Map<UUID, UUID> containers = new LinkedHashMap<>();
    /** Sheet nodes whose own switch is off: what is in them is compiled but stays off. */
    private final Set<UUID> disabledHolders = new java.util.HashSet<>();
    /** Signal outputs (a sheet's output pins, devices' redstone inputs) by what they do while their sheet is off. */
    private final Map<UUID, NodeSettings.OffBehavior> whileOff = new LinkedHashMap<>();
    /** Signal nodes that keep working while their sheet is off: those feeding an output set to keep running. */
    private final Set<UUID> keepRunning = new java.util.HashSet<>();

    public Set<UUID> disabledHolders() {
        return disabledHolders;
    }

    /** What a signal output does while its sheet is off (null for other signal nodes: they hold). */
    @Nullable
    public NodeSettings.OffBehavior whileOff(UUID node) {
        return whileOff.get(node);
    }

    public Set<UUID> keepRunning() {
        return keepRunning;
    }

    /** Whether a node sits in one of these sheet nodes' sheets (or in a sheet inside one of them). */
    public boolean inside(UUID node, Set<UUID> holders) {
        if (holders.isEmpty()) {
            return false;
        }
        UUID current = node;
        for (int hops = 0; current != null && hops < 256; hops++) {
            if (holders.contains(current)) {
                return true;
            }
            current = holder(current);
        }
        return false;
    }

    /** Node ids of the sources and sinks a device node is made of, mapped back to the device node (for metrics). */
    private final Map<UUID, UUID> owners = new LinkedHashMap<>();

    /**
     * The node the editor shows for a node id: a device node for its internal sources and sinks, the copy
     * node for anything pasted in from a blueprint, else the node itself.
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

    private static final int MAX_COPY_DEPTH = 8;
    /**
     * How big a sheet may get with its blueprints pasted in, and how much work finding its routes may take. Blueprints
     * inside blueprints multiply, and so do splitters whose branches meet again; past these the sheet is refused with
     * an error instead of stalling the server while it compiles.
     */
    private static final int MAX_EXPANDED_NODES = 16_384;
    private static final int MAX_EXPANDED_WIRES = 32_768;
    private static final int MAX_WALK_STEPS = 500_000;
    private boolean tooBig;
    private int walkSteps;

    private void tooBig(String what) {
        if (!tooBig) {
            tooBig = true;
            error(null, "This sheet is too big to run: " + what);
        }
    }

    /**
     * Copies nodes into {@code out}, replacing every sheet node with that sheet's own nodes and wires (under their own
     * ids) and every copy node with a copy of that blueprint's (under new ids). Wires on either node are moved to
     * the pins inside, by slot. {@code using} is the chain of sheets being pasted (to refuse loops); {@code on} is the
     * sheet these nodes show on.
     */
    private void paste(Network network, List<Node> from, List<Node> out, List<Wire> wires, List<UUID> using, UUID on) {
        for (Node node : from) {
            boolean child = node.settings() instanceof NodeSettings.SheetSettings;
            // A switched-off sheet still compiles (marked off), so its signal outputs can hold while it is off.
            if (!node.enabled() && !child) {
                continue;
            }
            if (!node.enabled()) {
                disabledHolders.add(node.id());
            }
            nodeSheet.putIfAbsent(node.id(), on);
            if (!child && !(node.settings() instanceof NodeSettings.CopySettings)) {
                out.add(node);
                continue;
            }
            if (node.gate() == Node.Gate.PULSE) {
                pulseGates.add(node.id());
            }
            UUID usedId = child ? ((NodeSettings.SheetSettings) node.settings()).sheet() : ((NodeSettings.CopySettings) node.settings()).sheet();
            Sheet used = network.sheet(usedId);
            if (used == null || used.blueprint() != !child) {
                error(node, describe(node) + (child ? ": the sheet it holds no longer exists"
                        : ": the blueprint it uses no longer exists or is no longer marked as a blueprint"));
                continue;
            }
            long copyDepth = using.stream().map(network::sheet).filter(s -> s != null && s.blueprint()).count();
            if (using.contains(used.id()) || (!child && copyDepth >= MAX_COPY_DEPTH)) {
                error(node, describe(node) + ": " + (child ? "sheet" : "blueprint") + " '" + used.name() + "' contains itself");
                continue;
            }
            dependencies.put(used.id(), used.revision());
            if (out.size() + used.nodes().size() > MAX_EXPANDED_NODES || wires.size() + used.wires().size() > MAX_EXPANDED_WIRES) {
                tooBig("with its sheets and blueprints filled in it has more than " + MAX_EXPANDED_NODES + " nodes or "
                        + MAX_EXPANDED_WIRES + " wires");
                return;
            }
            // A child sheet keeps its own ids (it is placed once, and the editor shows its nodes' activity); a
            // blueprint's copy gets ids of its own, made from the copy node's.
            java.util.function.Function<UUID, UUID> copy = child ? id -> id
                    : id -> UUID.nameUUIDFromBytes((node.id() + "/" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<Node> copies = new ArrayList<>();
            Map<UUID, NodeSettings.Binding> bindings = child ? Map.of() : ((NodeSettings.CopySettings) node.settings()).bindings();
            Map<GlobalPos, NodeSettings.Binding> byLayout = child ? Map.of() : io.github.leadassimilator.reticulum.graph.Blueprints.byLayout(used, bindings);
            for (Node inner : used.nodes()) {
                Node pasted = child ? inner : inner.withId(copy.apply(inner.id()));
                if (!child) {
                    // A copy fills its blueprint's slots with blocks; one left empty can't run, nor can routes through it.
                    NodeSettings bound = io.github.leadassimilator.reticulum.graph.Blueprints.bound(inner, bindings, byLayout);
                    if (bound == null) {
                        error(node, describe(node) + ": its slot " + io.github.leadassimilator.reticulum.graph.Blueprints.slotName(inner)
                                + " has no block yet (open the copy and drop a device on it)");
                        continue;
                    }
                    pasted = pasted.with(bound);
                }
                copies.add(pasted);
                if (child) {
                    containers.put(pasted.id(), node.id());
                } else {
                    owners.put(pasted.id(), node.id());
                }
            }
            if (child) {
                sheetNodes.put(used.id(), node.id());
                sheetTiming.put(used.id(), new int[] {used.interval(), used.priority()});
            }
            for (Wire inner : used.wires()) {
                wires.add(child ? inner : new Wire(copy.apply(inner.id()), copy.apply(inner.from()), inner.output(), copy.apply(inner.to()),
                        inner.input(), inner.order()));
            }
            for (int i = 0; i < wires.size(); i++) {
                Wire w = wires.get(i);
                if (w.to().equals(node.id()) && w.input() != Wire.GATE) {
                    Node pin = used.pin(true, w.input());
                    wires.set(i, pin == null ? w : new Wire(w.id(), w.from(), w.output(), copy.apply(pin.id()), 0, w.order()));
                } else if (w.from().equals(node.id())) {
                    Node pin = used.pin(false, w.output());
                    wires.set(i, pin == null ? w : new Wire(w.id(), copy.apply(pin.id()), Wire.MATCH, w.to(), w.input(), w.order()));
                }
            }
            List<UUID> deeper = new ArrayList<>(using);
            deeper.add(used.id());
            paste(network, copies, out, wires, deeper, child ? used.id() : on);
        }
    }

    private static UUID side(UUID device, String direction, int index) {
        return UUID.nameUUIDFromBytes((device + ":" + direction + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void expand(Node node, NodeSettings.DeviceSettings device, Map<UUID, Node> nodes) {
        for (int i = 0; i < device.outputs().size(); i++) {
            NodeSettings.Output out = device.outputs().get(i);
            if (!out.resource().moves()) {
                continue;
            }
            NodeSettings.Port port = new NodeSettings.Port(out.resource(), device.device(), out.faces(), out.slots());
            Node source = new Node(side(node.id(), "out", i),
                    new NodeSettings.SourceSettings(port, out.keep(), out.perOperation(), out.interval(), out.priority()),
                    node.label(), true, node.x(), node.y(), Node.Gate.WHILE_ON);
            nodes.put(source.id(), source);
            owners.put(source.id(), node.id());
        }
        for (int i = 0; i < device.inputs().size(); i++) {
            NodeSettings.Input in = device.inputs().get(i);
            if (!in.resource().moves()) {
                continue;
            }
            NodeSettings.Port port = new NodeSettings.Port(in.resource(), device.device(), in.faces(), in.slots());
            Node sink = new Node(side(node.id(), "in", i), new NodeSettings.SinkSettings(port, in.caps()), node.label(), true, node.x(), node.y(),
                    Node.Gate.WHILE_ON);
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
        /** Node ids as compiled (not mapped to the node the editor shows), for redstone. */
        final List<UUID> raw = new ArrayList<>();
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
            w.raw.addAll(raw);
            w.kit = kit;
            w.gatesBeforeKit = gatesBeforeKit;
            return w;
        }
    }

    private void walk(Node source, Node node, ResourceKind resource, Map<UUID, Node> nodes, Map<UUID, List<Wire>> outgoing,
            Map<UUID, Gate> gates, Walk walk, List<Route> routes) {
        if (++walkSteps > MAX_WALK_STEPS) {
            tooBig("its splitters and filters branch and join again into too many routes");
            return;
        }
        if (!walk.onPath.add(node.id())) {
            error(node, "Wires loop back to " + describe(node) + " — resource wires must not form a cycle");
            return;
        }
        walk.path.add(owners.getOrDefault(node.id(), node.id()));
        if (node.kind().signal()) {
            error(node, describe(node) + " takes signals, not resources");
            return;
        }
        if (!(node.settings() instanceof NodeSettings.SinkSettings)) {
            walk.raw.add(node.id());
        }
        switch (node.settings()) {
            case NodeSettings.SinkSettings sink -> {
                if (sink.port().resource() != resource) {
                    error(node, describe(node) + " takes " + sink.port().resource().getSerializedName()
                            + " but is wired to a " + resource.getSerializedName() + " source");
                } else {
                    walk.raw.add(node.id());
                    routes.add(new Route(source, node, List.copyOf(walk.gates), List.copyOf(walk.rateLimits),
                            List.copyOf(walk.branches), walk.kit, walk.gatesBeforeKit, List.copyOf(walk.path), List.copyOf(walk.wires),
                            controls(walk.raw)));
                }
                return;
            }
            case NodeSettings.RateLimitSettings rate -> walk.rateLimits.add(node);
            case NodeSettings.KitSettings kit -> {
                if (resource != ResourceKind.ITEM) {
                    error(node, describe(node) + ": kits only move items");
                    return;
                }
                if (walk.kit != null) {
                    error(node, describe(node) + ": a route can pass through only one kit");
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
        int[] weights = new int[wires.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = from.settings() instanceof NodeSettings.SplitterSettings s && mode == NodeSettings.SplitMode.WEIGHTED ? s.weight(i) : 1;
        }
        for (int i = 0; i < wires.size(); i++) {
            Walk next = walk.copy();
            if (wires.size() > 1) {
                next.branches.add(new Branch(from.id(), i, wires.size(), mode, weights));
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
            error(node, describe(node) + ": " + e.getMessage());
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
        // Every filter a node uses besides a Filter node's own: keep rules, caps, kit lines and Level sensors. Compiled
        // once here, so a bad one is reported in the editor (and matches nothing) instead of failing quietly each tick.
        List<String> expressions = switch (node.settings()) {
            case NodeSettings.SourceSettings s -> s.keep().stream().map(NodeSettings.Limit::filter).toList();
            case NodeSettings.SinkSettings s -> s.caps().stream().map(NodeSettings.Limit::filter).toList();
            case NodeSettings.KitSettings s -> s.lines().stream().map(NodeSettings.KitLine::filter).toList();
            case NodeSettings.LevelSettings s -> List.of(s.filter());
            default -> List.of();
        };
        for (String expression : expressions) {
            try {
                if (!itemLimitFilters.containsKey(expression)) {
                    itemLimitFilters.put(expression, Filters.items(expression, templates));
                }
                fluidLimitFilters.computeIfAbsent(expression, e -> fluidsOrAny(e, templates));
                chemicalLimitFilters.computeIfAbsent(expression, e -> chemicalsOrNone(e, templates));
            } catch (FilterException e) {
                error(node, describe(node) + ": " + e.getMessage());
            }
        }
    }

    /** The compiled filter of a keep rule or cap. Rules whose filter failed to compile match nothing. */
    public Filters.ItemFilter itemFilter(NodeSettings.Limit limit) {
        return itemFilter(limit.filter());
    }

    public Filters.FluidFilter fluidFilter(NodeSettings.Limit limit) {
        return fluidFilter(limit.filter());
    }

    public Filters.ChemicalFilter chemicalFilter(NodeSettings.Limit limit) {
        return chemicalFilter(limit.filter());
    }

    /** A node's compiled filter (keep rule, cap, kit line, Level sensor); one that failed to compile matches nothing. */
    public Filters.ItemFilter itemFilter(String expression) {
        return itemLimitFilters.getOrDefault(expression, (s, c) -> false);
    }

    public Filters.FluidFilter fluidFilter(String expression) {
        return fluidLimitFilters.getOrDefault(expression, (s, c) -> false);
    }

    public Filters.ChemicalFilter chemicalFilter(String expression) {
        return chemicalLimitFilters.getOrDefault(expression, Filters.ChemicalFilter.NONE);
    }

    /** How a node is named in problems: its label, a device node by its device's name, else its kind and id. */
    private String describe(Node node) {
        if (node.label().isPresent()) {
            return node.label().get();
        }
        if (network != null && node.settings() instanceof NodeSettings.DeviceSettings device && network.device(device.device()) != null) {
            var record = network.device(device.device());
            return record.name() != null ? record.name() : record.blockId().getPath().replace('_', ' ') + " at " + device.device().pos().toShortString();
        }
        return node.kind().getSerializedName() + " " + node.id().toString().substring(0, 8);
    }
}
