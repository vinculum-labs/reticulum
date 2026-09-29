package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Redstone;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The redstone side of a network, kept between ticks: signal values, what pulses, latches, counters and delays
 * remember, published network signals, redstone conditions and what the connectors give out.
 */
final class Signals {
    private static final long ON = 15;

    /** Output of every signal node, by compiled node id, as of this tick. */
    private final Map<UUID, Long> values = new HashMap<>();
    /** Whether each edge-watching input (pulse nodes, counters, pulse conditions) was on last tick. */
    private final Map<String, Boolean> wasOn = new HashMap<>();
    private final Map<UUID, Integer> pulseLeft = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> delays = new HashMap<>();
    /** Network signals transmitted last tick; what Receiver nodes and conditions read this tick. */
    private Map<String, Long> published = Map.of();
    /** Redstone to give a device: on every face ({@code face} null) or on one face (the connector touching it). */
    record Emit(GlobalPos device, @Nullable Direction face) {
    }

    /** Redstone the connectors currently give out, per device (and face). */
    private Map<Emit, Integer> emitting = Map.of();
    /** Condition results worked out this tick, so every sheet in one folder sees the same pulse. */
    private final Map<String, Verdict> verdicts = new HashMap<>();
    /** Nodes switched off by redstone this tick, and nodes whose pulse fired this tick. */
    private final Set<UUID> off = new HashSet<>();
    private final Set<UUID> pulsed = new HashSet<>();

    enum Verdict {
        ON, OFF, PULSED
    }

    Map<UUID, Long> values() {
        return values;
    }

    Map<String, Long> published() {
        return published;
    }

    Set<UUID> off() {
        return off;
    }

    Set<UUID> pulsed() {
        return pulsed;
    }

    void startTick() {
        verdicts.clear();
        off.clear();
        pulsed.clear();
    }

    // ---------------------------------------------------------------- conditions

    /** Checks a condition once per tick per {@code key}; {@code device} is the default source of device nodes. */
    Verdict check(MinecraftServer server, Network network, String key, Redstone redstone, @Nullable GlobalPos device) {
        if (redstone.ignored()) {
            return Verdict.ON;
        }
        Verdict cached = verdicts.get(key);
        if (cached != null) {
            return cached;
        }
        boolean high = read(server, network, redstone, device) > 0;
        Verdict verdict = switch (redstone.mode()) {
            case HIGH -> high ? Verdict.ON : Verdict.OFF;
            case LOW -> high ? Verdict.OFF : Verdict.ON;
            case PULSE -> {
                Boolean before = wasOn.put(key, high);
                yield high && !Boolean.TRUE.equals(before) ? Verdict.PULSED : Verdict.OFF;
            }
            case IGNORED -> Verdict.ON;
        };
        verdicts.put(key, verdict);
        return verdict;
    }

    private long read(MinecraftServer server, Network network, Redstone redstone, @Nullable GlobalPos device) {
        return switch (redstone.source()) {
            case DEFAULT -> device != null ? atConnectorsOf(server, network, device) : atController(server, network);
            case CONNECTOR -> redstone.connector().map(network::connector).map(c -> (long) received(server, c.pos())).orElse(0L);
            case FACE -> redstone.device().map(pos -> givenOut(server, pos, redstone.face(), false)).orElse(0);
            case SIGNAL -> published.getOrDefault(redstone.signal(), 0L);
        };
    }

    static int atController(MinecraftServer server, Network network) {
        return network.controller() == null ? 0 : received(server, network.controller());
    }

    /** The strongest signal any connector reaching the device receives, as XNet reads a connector. */
    private static int atConnectorsOf(MinecraftServer server, Network network, GlobalPos device) {
        int best = 0;
        for (ConnectorRecord connector : network.connectors()) {
            if (connector.devices().contains(device)) {
                best = Math.max(best, received(server, connector.pos()));
            }
        }
        return best;
    }

    /** The signal a block receives from its neighbors. */
    static int received(MinecraftServer server, GlobalPos at) {
        ServerLevel level = server.getLevel(at.dimension());
        return level != null && level.isLoaded(at.pos()) ? level.getBestNeighborSignal(at.pos()) : 0;
    }

    /** The signal a device gives out of one face (or its strongest face), or its comparator value. */
    static int givenOut(MinecraftServer server, GlobalPos device, Optional<FaceKey> face, boolean comparator) {
        ServerLevel level = server.getLevel(device.dimension());
        BlockPos pos = device.pos();
        if (level == null || !level.isLoaded(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        if (comparator) {
            return state.hasAnalogOutputSignal() ? state.getAnalogOutputSignal(level, pos) : 0;
        }
        Direction only = face.map(FaceKey::direction).orElse(null);
        int best = 0;
        for (Direction side : Direction.values()) {
            if (only == null || only == side) {
                // Level.getSignal takes the direction from the receiving block towards the one giving the signal.
                best = Math.max(best, level.getSignal(pos, side.getOpposite()));
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- signal nodes

    /** Works out every signal node of the running sheets, then applies Redstone out nodes to the connectors. */
    void evaluate(NetworkRuntime.Context ctx, List<CompiledSheet> sheets) {
        Map<String, Long> publishing = new HashMap<>();
        Map<Emit, Integer> emit = new HashMap<>();
        for (CompiledSheet sheet : sheets) {
            for (CompiledSheet.SignalNode node : sheet.signals()) {
                values.put(node.node().id(), output(ctx, sheet, node, publishing, emit));
            }
            for (CompiledSheet.SignalNode node : sheet.signals()) {
                remember(ctx, node);
            }
        }
        published = publishing;
        applyEmissions(ctx.server(), ctx.network(), emit);
    }

    private long in(CompiledSheet.SignalNode node, int input) {
        long best = 0;
        for (UUID from : node.input(input)) {
            best = Math.max(best, values.getOrDefault(from, 0L));
        }
        return best;
    }

    /** The highest value among the nodes wired into a gate (0 when they aren't worked out, e.g. switched off). */
    long gate(List<UUID> from) {
        long best = 0;
        for (UUID id : from) {
            best = Math.max(best, values.getOrDefault(id, 0L));
        }
        return best;
    }

    private long output(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.SignalNode node, Map<String, Long> publishing,
            Map<Emit, Integer> emit) {
        UUID id = node.node().id();
        String key = id.toString();
        return switch (node.node().settings()) {
            case NodeSettings.RedstoneInSettings in -> {
                if (in.comparator() || in.faces().isEmpty()) {
                    yield givenOut(ctx.server(), in.device(), Optional.empty(), in.comparator());
                }
                int best = 0;
                for (FaceKey face : in.faces()) {
                    best = Math.max(best, givenOut(ctx.server(), in.device(), Optional.of(face), false));
                }
                yield best;
            }
            case NodeSettings.RedstoneOutSettings out -> {
                int level = (int) Math.max(0, Math.min(15, in(node, 0)));
                if (out.faces().isEmpty()) {
                    emit.merge(new Emit(out.device(), null), level, Math::max);
                } else {
                    for (FaceKey face : out.faces()) {
                        if (face.direction() != null) {
                            emit.merge(new Emit(out.device(), face.direction()), level, Math::max);
                        }
                    }
                }
                yield level;
            }
            case NodeSettings.LevelSettings level -> count(ctx, sheet, level);
            case NodeSettings.CompareSettings compare -> {
                long b = node.input(1).isEmpty() ? compare.constant() : in(node, 1);
                yield compare.op().test(in(node, 0), b) ? ON : 0;
            }
            case NodeSettings.LogicSettings logic -> {
                int on = 0;
                for (UUID from : node.input(0)) {
                    if (values.getOrDefault(from, 0L) > 0) {
                        on++;
                    }
                }
                yield logic.op().test(on, node.input(0).size()) ? ON : 0;
            }
            case NodeSettings.TimerSettings timer -> {
                boolean running = node.input(0).isEmpty() || in(node, 0) > 0;
                yield running && Math.floorMod(ctx.runtime().ticks(), Math.max(1, timer.interval())) == 0 ? ON : 0;
            }
            case NodeSettings.PulseSettings pulse -> {
                boolean high = in(node, 0) > 0;
                Boolean before = wasOn.put(key, high);
                if (high && !Boolean.TRUE.equals(before)) {
                    pulseLeft.put(id, Math.max(1, pulse.length()));
                }
                int left = pulseLeft.getOrDefault(id, 0);
                if (left > 0) {
                    pulseLeft.put(id, left - 1);
                }
                yield left > 0 ? ON : 0;
            }
            case NodeSettings.LatchSettings latch -> ctx.network().signalMemory().getOrDefault(key, 0L);
            case NodeSettings.CounterSettings counter -> ctx.network().signalMemory().getOrDefault(key, 0L);
            case NodeSettings.DelaySettings delay -> {
                ArrayDeque<Long> queue = delays.get(id);
                yield queue != null && queue.size() >= Math.max(1, delay.ticks()) ? queue.peekFirst() : 0;
            }
            case NodeSettings.TransmitSettings transmit -> {
                long value = in(node, 0);
                if (!transmit.name().isBlank()) {
                    publishing.merge(transmit.name(), value, Math::max);
                }
                yield value;
            }
            case NodeSettings.ReceiveSettings receive -> published.getOrDefault(receive.name(), 0L);
            // Sheet pins pass signals through a sub-sheet's edge.
            default -> {
                long best = 0;
                for (int i = 0; i < node.inputs().size(); i++) {
                    best = Math.max(best, in(node, i));
                }
                yield best;
            }
        };
    }

    /** After every node has its output: latches, counters and delays take in this tick's inputs. */
    private void remember(NetworkRuntime.Context ctx, CompiledSheet.SignalNode node) {
        UUID id = node.node().id();
        String key = id.toString();
        Map<String, Long> memory = ctx.network().signalMemory();
        switch (node.node().settings()) {
            case NodeSettings.LatchSettings latch -> {
                long now = in(node, 1) > 0 ? 0 : in(node, 0) > 0 ? ON : memory.getOrDefault(key, 0L);
                store(ctx, key, now);
            }
            case NodeSettings.CounterSettings counter -> {
                boolean high = in(node, 0) > 0;
                Boolean before = wasOn.put(key, high);
                boolean rising = high && !Boolean.TRUE.equals(before);
                long count = memory.getOrDefault(key, 0L);
                store(ctx, key, in(node, 1) > 0 ? 0 : rising ? count + 1 : count);
            }
            case NodeSettings.DelaySettings delay -> {
                ArrayDeque<Long> queue = delays.computeIfAbsent(id, k -> new ArrayDeque<>());
                queue.addLast(in(node, 0));
                while (queue.size() > Math.max(1, delay.ticks())) {
                    queue.removeFirst();
                }
            }
            default -> {
            }
        }
    }

    private static void store(NetworkRuntime.Context ctx, String key, long value) {
        Map<String, Long> memory = ctx.network().signalMemory();
        long before = memory.getOrDefault(key, 0L);
        if (before != value) {
            if (value == 0) {
                memory.remove(key);
            } else {
                memory.put(key, value);
            }
            // Saved with the world, but not an edit: no new snapshot for the editor.
            NetworkManager.data(ctx.server()).setDirty();
        }
    }

    /** How much of a resource a device holds (items, mB or FE), counting only what matches the node's filter. */
    private static long count(NetworkRuntime.Context ctx, CompiledSheet sheet, NodeSettings.LevelSettings level) {
        NodeSettings.Port port = new NodeSettings.Port(level.resource(), level.device(), level.faces(), Optional.empty());
        ServerLevel world = ctx.server().getLevel(level.device().dimension());
        if (world == null) {
            return 0;
        }
        Filters.Context context = Filters.Context.of(world);
        String expression = level.filter();
        try {
            return switch (level.resource()) {
                case ITEM -> {
                    Filters.ItemFilter filter = Filters.items(expression, ctx.network().filters());
                    yield ctx.runtime().itemPort(ctx.server(), ctx.network(), port).count(stack -> filter.test(stack, context));
                }
                case FLUID -> {
                    Filters.FluidFilter filter = Filters.fluids(expression, ctx.network().filters());
                    long total = 0;
                    for (IFluidHandler handler : ctx.runtime().fluidView(ctx.server(), ctx.network(), port)) {
                        for (int tank = 0; tank < handler.getTanks(); tank++) {
                            FluidStack fluid = handler.getFluidInTank(tank);
                            if (!fluid.isEmpty() && filter.test(fluid, context)) {
                                total += fluid.getAmount();
                            }
                        }
                    }
                    yield total;
                }
                case ENERGY -> {
                    long total = 0;
                    for (IEnergyStorage storage : ctx.runtime().energyView(ctx.server(), ctx.network(), port)) {
                        total += storage.getEnergyStored();
                    }
                    yield total;
                }
                case REDSTONE, ME -> 0;
                case CHEMICAL -> Mods.MEKANISM ? ChemicalMover.count(ctx, port, Filters.chemicals(expression, ctx.network().filters()), context) : 0;
            };
        } catch (FilterException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- redstone out

    /** Gives each connector's redstone to the devices it faces, touching only what changed. */
    void applyEmissions(MinecraftServer server, Network network, Map<Emit, Integer> emit) {
        if (emit.isEmpty() && emitting.isEmpty()) {
            return;
        }
        Set<GlobalPos> devices = new HashSet<>();
        emit.keySet().forEach(e -> devices.add(e.device()));
        emitting.keySet().forEach(e -> devices.add(e.device()));
        for (ConnectorRecord connector : network.connectors()) {
            for (GlobalPos device : connector.devices()) {
                if (!devices.contains(device) || !device.dimension().equals(connector.pos().dimension())) {
                    continue;
                }
                ServerLevel level = server.getLevel(device.dimension());
                if (level == null || !level.isLoaded(connector.pos().pos())
                        || !(level.getBlockEntity(connector.pos().pos()) instanceof ConnectorBlockEntity be)) {
                    continue;
                }
                Direction toward = Direction.fromDelta(device.pos().getX() - connector.pos().pos().getX(),
                        device.pos().getY() - connector.pos().pos().getY(), device.pos().getZ() - connector.pos().pos().getZ());
                // The device's face touching this connector is the opposite of the way the connector faces it.
                int power = toward == null ? 0 : Math.max(emit.getOrDefault(new Emit(device, null), 0),
                        emit.getOrDefault(new Emit(device, toward.getOpposite()), 0));
                if (toward != null && be.setPower(toward, power)) {
                    level.updateNeighborsAt(connector.pos().pos(), be.getBlockState().getBlock());
                }
            }
        }
        emitting = emit;
    }

    /** The network stopped (switched off, or its controller went away): connectors stop giving out redstone. */
    void stop(MinecraftServer server, Network network) {
        applyEmissions(server, network, Map.of());
        values.clear();
        published = Map.of();
    }

    // ---------------------------------------------------------------- switching

    /** Works out which nodes of a sheet redstone switches off this tick, and which pulse. */
    void switchNodes(MinecraftServer server, Network network, CompiledSheet sheet) {
        for (Map.Entry<UUID, CompiledSheet.Condition> entry : sheet.conditions().entrySet()) {
            Verdict verdict = check(server, network, "node:" + entry.getKey(), entry.getValue().redstone(), entry.getValue().device());
            if (verdict == Verdict.OFF) {
                off.add(entry.getKey());
            } else if (verdict == Verdict.PULSED) {
                pulsed.add(entry.getKey());
            }
        }
        for (Map.Entry<UUID, List<UUID>> entry : sheet.switchedBy().entrySet()) {
            if (gate(entry.getValue()) <= 0) {
                off.add(entry.getKey());
            }
        }
    }
}
