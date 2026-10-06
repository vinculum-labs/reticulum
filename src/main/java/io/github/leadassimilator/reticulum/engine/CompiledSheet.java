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
 * is pasted as an instance. Compiled again whenever a sheet in it changes; problems are collected in {@link #errors()} and
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
     * the device nodes their sources and sinks belong to, and the instance nodes they were pasted from.
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
    /** For every node as compiled (pasted instances, device sides and pins included): the sheet it shows on. */
    private final Map<UUID, UUID> nodeSheet = new LinkedHashMap<>();

    /**
     * Every node id this compiles (pasted from sheets and instances, and the pins and sides made for devices): what
     * the engine keeps per node for any other id is for a node that is gone.
     */
    public Set<UUID> nodeIds() {
        Set<UUID> out = new HashSet<>(nodeSheet.keySet());
        out.addAll(owners.keySet());
        return out;
    }
    /** Each sheet's operation interval and priority, by sheet id, for what is compiled from it. */
    private final Map<UUID, int[]> sheetTiming = new LinkedHashMap<>();
    /** Each instance node's blueprint's interval and priority, for what runs in it. */
    private final Map<UUID, int[]> instanceTiming = new LinkedHashMap<>();
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

    /** Ticks between item operations for what is compiled from this node's sheet (in an instance, its blueprint's). */
    public int intervalOf(UUID node) {
        int[] timing = timing(node);
        return timing == null ? sheet.interval() : timing[0];
    }

    /** The priority of this node's sheet (in an instance, its blueprint's): sheets with a higher one move first. */
    public int priorityOf(UUID node) {
        int[] timing = timing(node);
        return timing == null ? sheet.priority() : timing[1];
    }

    /** The interval and priority a node runs with: the innermost instance's blueprint's, else its sheet's. */
    @Nullable
    private int[] timing(UUID node) {
        UUID current = node;
        for (int hops = 0; current != null && hops < 32; hops++) {
            int[] instance = instanceTiming.get(current);
            if (instance != null) {
                return instance;
            }
            current = owners.get(current);
        }
        return sheetTiming.get(sheetOf(node));
    }

    /** The sheet node of each sheet in the tree, by sheet id. */
    public Map<UUID, UUID> sheetNodes() {
        return sheetNodes;
    }

    /** Every instance node pasted in (an instance inside an instance under its pasted id): each switches what it holds, as a sheet node does. */
    public Set<UUID> instanceNodes() {
        return instanceNodes;
    }

    /** The sheet node holding a node pasted in from a child sheet (a sheet node for one inside another), or null. */
    @Nullable
    public UUID containerOf(UUID node) {
        return containers.get(node);
    }

    /**
     * Something that keeps part of the sheet from running. {@code node} is the node it is about, as compiled (a device's
     * side or a node pasted from a blueprint); {@link #owner} maps it to the node the editor shows. {@code detail} is
     * the message without the node's name in front (when it starts with it), for the editor to show the node itself.
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
            // One node per device (a slot an instance fills counts): a second one is flagged, though it still runs. An empty
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

    /** A signal node's outputs past its first (a Sequencer's tracks) as the editor names them: "<node>/out/<n>". */
    private final Map<UUID, String> outputNames = new LinkedHashMap<>();

    public Map<UUID, String> outputNames() {
        return outputNames;
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
        pinHolders.add(device.id());
        pinKeys.put(id, key);
        return new Node(id, settings, device.label(), true, device.x(), device.y(), Node.Gate.WHILE_ON, java.util.Optional.empty());
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
    /** {@code controls}: both ends' nodes and everything holding them; switched off (or gated off), the link goes. */
    public record MeLink(String pinA, UUID connectorA, Direction sideA, String pinB, UUID connectorB, Direction sideB, Set<UUID> controls) {
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
                        pinKeys.getOrDefault(wire.to(), wire.to().toString()), b.connector(), b.side(), controls(List.of(wire.from(), wire.to()))));
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
    /**
     * Signal nodes left out because they can't run (switched off, or a Level sensor whose slot has no block): their
     * wires still count, carrying 0, so what they gate is off and what they feed sees 0 rather than nothing wired.
     */
    private final Set<UUID> silent = new HashSet<>();
    /** Switched-off device nodes among {@link #silent}: silent only on their redstone outputs (their others just stop). */
    private final Map<UUID, NodeSettings.DeviceSettings> silentDevices = new java.util.HashMap<>();

    /** Whether a wire leaves a silent signal output: a switched-off signal node, or a switched-off device's redstone side. */
    private boolean silentSignal(Wire wire) {
        if (!silent.contains(wire.from())) {
            return false;
        }
        NodeSettings.DeviceSettings device = silentDevices.get(wire.from());
        return device == null || wire.output() >= 0 && wire.output() < device.outputs().size()
                && device.outputs().get(wire.output()).resource() == ResourceKind.REDSTONE;
    }
    /** Nodes (by compiled id, including instance nodes) whose gate runs them once per pulse. */
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
        return kind == NodeSettings.Kind.LATCH || kind == NodeSettings.Kind.COUNTER || kind == NodeSettings.Kind.DELAY
                || kind == NodeSettings.Kind.SEQUENCER;
    }

    /**
     * The key a signal node's output goes by: output 0 is the node's own id; another (a Sequencer's tracks) has an id of
     * its own, so wires from different outputs read different values.
     */
    public static UUID outputKey(UUID node, int output) {
        return output <= 0 ? node : UUID.nameUUIDFromBytes((node + "/out/" + output).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        // A sheet pin feeding a gate or a signal node carries a signal, whatever feeds it (a switched-off node, or
        // nothing: an unwired pin gives 0 rather than leaving what it feeds unwired, which often reads as on).
        for (Wire wire : wires) {
            Node from = byId.get(wire.from());
            Node to = byId.get(wire.to());
            if (from != null && from.settings() instanceof NodeSettings.SheetPinSettings
                    && (wire.input() == Wire.GATE || to != null && to.kind().signal())) {
                carriers.add(from.id());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Wire wire : wires) {
                Node to = byId.get(wire.to());
                boolean signalIn = carriers.contains(wire.from()) || silentSignal(wire);
                if (signalIn && to != null && to.settings() instanceof NodeSettings.SheetPinSettings && carriers.add(to.id())) {
                    grew = true;
                }
            }
        }
        if (carriers.isEmpty()) {
            for (Wire wire : wires) {
                if (wire.input() == Wire.GATE && silentSignal(wire)) {
                    switchedBy.computeIfAbsent(wire.to(), k -> new ArrayList<>()).add(outputKey(wire.from(), wire.output()));
                } else if (wire.input() == Wire.GATE) {
                    error(byId.get(wire.to()), "Only signal wires can switch a node on and off");
                }
            }
            return carriers;
        }
        Map<UUID, List<List<UUID>>> inputs = new LinkedHashMap<>();
        // Output keys (see outputKey) back to their nodes.
        Map<UUID, UUID> keyOwners = new java.util.HashMap<>();
        for (Wire wire : wires) {
            if (silent.contains(wire.from()) && !silentSignal(wire)) {
                // A switched-off device's item, fluid or energy side: nothing goes out of it (into a gate it is wrong, on
                // or off).
                if (wire.input() == Wire.GATE) {
                    error(byId.get(wire.to()), "Only signal wires can switch a node on and off");
                }
                continue;
            }
            if (silent.contains(wire.from())) {
                // Never worked out, so it reads 0; not in keyOwners, so nothing waits on it.
                UUID key = outputKey(wire.from(), wire.output());
                if (wire.input() == Wire.GATE) {
                    switchedBy.computeIfAbsent(wire.to(), k -> new ArrayList<>()).add(key);
                } else if (carriers.contains(wire.to())) {
                    List<List<UUID>> in = inputs.computeIfAbsent(wire.to(), k -> new ArrayList<>());
                    while (in.size() <= wire.input()) {
                        in.add(new ArrayList<>());
                    }
                    in.get(wire.input()).add(key);
                }
                continue;
            }
            if (!carriers.contains(wire.from())) {
                if (wire.input() == Wire.GATE) {
                    error(byId.get(wire.to()), "Only signal wires can switch a node on and off");
                }
                continue;
            }
            if (!byId.containsKey(wire.from())) {
                continue;
            }
            UUID key = outputKey(wire.from(), wire.output());
            keyOwners.put(key, wire.from());
            if (wire.output() > 0) {
                outputNames.put(key, wire.from() + "/out/" + wire.output());
            }
            if (wire.input() == Wire.GATE) {
                switchedBy.computeIfAbsent(wire.to(), k -> new ArrayList<>()).add(key);
            } else if (carriers.contains(wire.to())) {
                List<List<UUID>> in = inputs.computeIfAbsent(wire.to(), k -> new ArrayList<>());
                while (in.size() <= wire.input()) {
                    in.add(new ArrayList<>());
                }
                in.get(wire.input()).add(key);
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
                    UUID owner = keyOwners.getOrDefault(source, source);
                    Node feeder = byId.get(owner);
                    if (feeder != null && !remembers(feeder.kind())) {
                        count++;
                        feeds.computeIfAbsent(owner, k -> new ArrayList<>()).add(id);
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
                    UUID next = todo.poll();
                    SignalNode at = byNode.get(keyOwners.getOrDefault(next, next));
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
     * The given nodes plus everything that holds them: the device nodes of device sides, the instance nodes of pasted
     * instances, and the sheet nodes of the sheets they are on, all the way up, so switching any of those off stops them.
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

    /** What holds a compiled node: its device or instance node, else the sheet node of the sheet it was pasted from. */
    @Nullable
    private UUID holder(UUID node) {
        UUID owner = owners.get(node);
        return owner != null ? owner : containers.get(node);
    }

    /** For every node pasted in from a child sheet: that sheet's sheet node. Not an owner: the node shows as itself. */
    private final Map<UUID, UUID> containers = new LinkedHashMap<>();
    private final Set<UUID> instanceNodes = new java.util.HashSet<>();
    /** Device nodes with redstone (or ME) pins: gated off, their pins go dark as when switched off. */
    private final Set<UUID> pinHolders = new java.util.HashSet<>();

    public Set<UUID> pinHolders() {
        return pinHolders;
    }
    /** Sheet and instance nodes whose own switch is off: what is in them is compiled but stays off. */
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
    /** Nodes pasted in from a blueprint for an instance (not a device's sides, which are its device node's own). */
    private final Set<UUID> fromInstances = new HashSet<>();

    /** Whether a compiled node was pasted in from a blueprint for an instance. */
    public boolean fromInstance(UUID node) {
        return fromInstances.contains(node);
    }

    /**
     * The node the editor shows for a node id: a device node for its internal sources and sinks, the instance
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

    private static final int MAX_INSTANCE_DEPTH = 8;
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
     * ids) and every instance node with its blueprint's nodes and wires (under new ids). Wires on either node are moved to
     * the pins inside, by slot. {@code using} is the chain of sheets being pasted (to refuse loops); {@code on} is the
     * sheet these nodes show on.
     */
    private void paste(Network network, List<Node> from, List<Node> out, List<Wire> wires, List<UUID> using, UUID on) {
        for (Node node : from) {
            boolean child = node.settings() instanceof NodeSettings.SheetSettings;
            boolean holds = child || node.settings() instanceof NodeSettings.InstanceSettings;
            // A switched-off sheet or instance still compiles (marked off), so its signal outputs can hold while it is off.
            if (!node.enabled() && !holds) {
                if (node.kind().signal() || node.settings() instanceof NodeSettings.DeviceSettings) {
                    silent.add(node.id());
                }
                if (node.settings() instanceof NodeSettings.DeviceSettings device) {
                    silentDevices.put(node.id(), device);
                }
                continue;
            }
            if (!node.enabled()) {
                disabledHolders.add(node.id());
            }
            if (!child && holds) {
                instanceNodes.add(node.id());
            }
            nodeSheet.putIfAbsent(node.id(), on);
            if (!child && !(node.settings() instanceof NodeSettings.InstanceSettings)) {
                out.add(node);
                continue;
            }
            if (node.gate() == Node.Gate.PULSE) {
                pulseGates.add(node.id());
            }
            UUID usedId = child ? ((NodeSettings.SheetSettings) node.settings()).sheet() : ((NodeSettings.InstanceSettings) node.settings()).sheet();
            Sheet used = network.sheet(usedId);
            if (used == null || used.blueprint() != !child) {
                error(node, describe(node) + (child ? ": the sheet it holds no longer exists"
                        : ": the blueprint it uses no longer exists or is no longer marked as a blueprint"));
                continue;
            }
            long instanceDepth = using.stream().map(network::sheet).filter(s -> s != null && s.blueprint()).count();
            if (using.contains(used.id())) {
                error(node, describe(node) + ": " + (child ? "sheet" : "blueprint") + " '" + used.name() + "' contains itself");
                continue;
            }
            if (!child && instanceDepth >= MAX_INSTANCE_DEPTH) {
                error(node, describe(node) + ": blueprints can be placed in blueprints at most " + MAX_INSTANCE_DEPTH + " deep");
                continue;
            }
            dependencies.put(used.id(), used.revision());
            if (out.size() + used.nodes().size() > MAX_EXPANDED_NODES || wires.size() + used.wires().size() > MAX_EXPANDED_WIRES) {
                tooBig("with its sheets and blueprints filled in it has more than " + MAX_EXPANDED_NODES + " nodes or "
                        + MAX_EXPANDED_WIRES + " wires");
                return;
            }
            // A child sheet keeps its own ids (it is placed once, and the editor shows its nodes' activity); a
            // blueprint's instance gets ids of its own, made from the instance node's.
            java.util.function.Function<UUID, UUID> instance = child ? id -> id
                    : id -> UUID.nameUUIDFromBytes((node.id() + "/" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<Node> instances = new ArrayList<>();
            Map<UUID, NodeSettings.Binding> bindings = child ? Map.of() : ((NodeSettings.InstanceSettings) node.settings()).bindings();
            for (Node inner : used.nodes()) {
                Node pasted = child ? inner : inner.withId(instance.apply(inner.id()));
                if (!child) {
                    // An instance fills its blueprint's slots with blocks; one left empty can't run, nor can routes through it.
                    NodeSettings bound = io.github.leadassimilator.reticulum.graph.Blueprints.bound(inner, bindings);
                    if (bound == null) {
                        UUID needed = io.github.leadassimilator.reticulum.graph.Blueprints.slotNeeded(inner);
                        Node slot = needed == null ? null : used.node(needed);
                        // Checking a blueprint on its own (not running it), blueprints placed in it are filled by each of its
                        // placements, at any depth: nothing to fill here.
                        boolean filledElsewhere = slot != null && sheet.blueprint();
                        if (filledElsewhere) {
                            if (inner.kind().signal()) {
                                silent.add(pasted.id());
                            }
                            continue;
                        }
                        error(node, describe(node) + (slot == null
                                ? ": a Level sensor in it reads a slot the blueprint no longer has"
                                : ": its slot " + io.github.leadassimilator.reticulum.graph.Blueprints.slotName(slot)
                                        + " has no block yet (open it and drop a device on it)"));
                        if (inner.kind().signal()) {
                            silent.add(pasted.id());
                        }
                        continue;
                    }
                    pasted = pasted.with(bound);
                    // A blueprint placed in this one: filled by this placement, with the blocks it has for the inner one's
                    // slots (whatever the inner node itself holds is the blueprint's, and not used).
                    if (inner.settings() instanceof NodeSettings.InstanceSettings nested) {
                        pasted = pasted.with(new NodeSettings.InstanceSettings(nested.sheet(),
                                io.github.leadassimilator.reticulum.graph.Blueprints.inner(network, bindings, inner.id(), network.sheet(nested.sheet()))));
                    }
                    // A splitter's weights are by wire, and the instance's wires have ids of their own.
                    if (pasted.settings() instanceof NodeSettings.SplitterSettings splitter && !splitter.weights().isEmpty()) {
                        pasted = pasted.with(splitter.renamed(instance));
                    }
                }
                instances.add(pasted);
                if (child) {
                    containers.put(pasted.id(), node.id());
                } else {
                    owners.put(pasted.id(), node.id());
                    fromInstances.add(pasted.id());
                }
            }
            if (child) {
                sheetNodes.put(used.id(), node.id());
                sheetTiming.put(used.id(), new int[] {used.interval(), used.priority()});
            } else {
                instanceTiming.put(node.id(), new int[] {used.interval(), used.priority()});
            }
            for (Wire inner : used.wires()) {
                wires.add(child ? inner : new Wire(instance.apply(inner.id()), instance.apply(inner.from()), inner.output(), instance.apply(inner.to()),
                        inner.input(), inner.order()));
            }
            for (int i = 0; i < wires.size(); i++) {
                Wire w = wires.get(i);
                if (w.to().equals(node.id()) && w.input() != Wire.GATE) {
                    Node pin = used.pin(true, w.input());
                    wires.set(i, pin == null ? w : new Wire(w.id(), w.from(), w.output(), instance.apply(pin.id()), 0, w.order()));
                } else if (w.from().equals(node.id())) {
                    Node pin = used.pin(false, w.output());
                    wires.set(i, pin == null ? w : new Wire(w.id(), instance.apply(pin.id()), Wire.MATCH, w.to(), w.input(), w.order()));
                }
            }
            List<UUID> deeper = new ArrayList<>(using);
            deeper.add(used.id());
            paste(network, instances, out, wires, deeper, child ? used.id() : on);
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
                    node.label(), true, node.x(), node.y(), Node.Gate.WHILE_ON, java.util.Optional.empty());
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
                    Node.Gate.WHILE_ON, java.util.Optional.empty());
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
        // Counted under its own id (a device's input or output under its device node), and the instances it sits in once
        // each: an instance's nodes show their own activity in the open instance, and the instance node what passed through it.
        UUID shown = node.settings() instanceof NodeSettings.SourceSettings || node.settings() instanceof NodeSettings.SinkSettings
                ? owners.getOrDefault(node.id(), node.id())
                : node.id();
        walk.path.add(shown);
        for (UUID up = owners.get(shown); up != null && !walk.path.contains(up); up = owners.get(up)) {
            walk.path.add(up);
        }
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
            case NodeSettings.SheetPinSettings pin when pin.resource().isPresent() && pin.resource().get() != resource -> {
                error(node, describe(node) + " carries " + pin.resource().get().getSerializedName() + " but gets "
                        + resource.getSerializedName());
                return;
            }
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
                // Each output takes turns among its own wires: a turn on one doesn't move the other's on.
                follow(source, node, output == Wire.MATCH ? node.id() : outputKey(node.id(), output), branch, resource, nodes, outgoing, gates, next, routes);
            }
            return;
        }
        follow(source, node, node.id(), wires, resource, nodes, outgoing, gates, walk, routes);
    }

    /** {@code turns}: whose turn-taking the branches here share (the node's, or a filter output's). */
    private void follow(Node source, Node from, UUID turns, List<Wire> wires, ResourceKind resource, Map<UUID, Node> nodes,
            Map<UUID, List<Wire>> outgoing, Map<UUID, Gate> gates, Walk walk, List<Route> routes) {
        NodeSettings.SplitMode mode = from.settings() instanceof NodeSettings.SplitterSettings s ? s.mode() : NodeSettings.SplitMode.ROUND_ROBIN;
        int[] weights = new int[wires.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = from.settings() instanceof NodeSettings.SplitterSettings s && mode == NodeSettings.SplitMode.WEIGHTED ? s.weight(wires.get(i).id()) : 1;
        }
        for (int i = 0; i < wires.size(); i++) {
            Walk next = walk.copy();
            if (wires.size() > 1) {
                next.branches.add(new Branch(turns, i, wires.size(), mode, weights));
            }
            next.wires.add(wires.get(i).id());
            walk(source, nodes.get(wires.get(i).to()), resource, nodes, outgoing, gates, next, routes);
        }
    }

    @Nullable
    private Gate compileGate(Node node, String expression, Map<String, String> templates) {
        try {
            Filters.checkAny(expression, templates);
        } catch (FilterException e) {
            error(node, describe(node) + ": " + e.getMessage());
            return null;
        }
        return new Gate(itemsOrNone(expression, templates), fluidsOrAny(expression, templates), chemicalsOrNone(expression, templates), false);
    }

    /** A filter for fluids (temp:, say) may use predicates items don't have; such filters simply match no items. */
    private static Filters.ItemFilter itemsOrNone(String expression, Map<String, String> templates) {
        try {
            return Filters.items(expression, templates);
        } catch (FilterException e) {
            return (stack, context) -> false;
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
                Filters.checkAny(expression, templates);
            } catch (FilterException e) {
                error(node, describe(node) + ": " + e.getMessage());
                brokenLimits.add(expression);
                continue;
            }
            itemLimitFilters.computeIfAbsent(expression, e -> itemsOrNone(e, templates));
            fluidLimitFilters.computeIfAbsent(expression, e -> fluidsOrAny(e, templates));
            chemicalLimitFilters.computeIfAbsent(expression, e -> chemicalsOrNone(e, templates));
        }
    }

    /**
     * Whether a keep rule's or cap's filter failed to compile (a typo, a template deleted): it then lets nothing move,
     * rather than being skipped — a keep rule skipped would let the source be emptied.
     */
    public boolean broken(NodeSettings.Limit limit) {
        return brokenLimits.contains(limit.filter());
    }

    private final Set<String> brokenLimits = new HashSet<>();

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
        // A placed blueprint by the blueprint's name (in the editor it is just "a blueprint" placed here).
        if (network != null && node.settings() instanceof NodeSettings.InstanceSettings instance && network.sheet(instance.sheet()) != null) {
            return "blueprint '" + network.sheet(instance.sheet()).name() + "'";
        }
        return node.kind().getSerializedName() + " " + node.id().toString().substring(0, 8);
    }
}
