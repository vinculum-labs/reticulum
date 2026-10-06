package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayDeque;
import java.util.EnumSet;
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
 * remember, which gates switch their nodes and what the connectors give out.
 */
final class Signals {
    private static final long ON = 15;

    /** Output of every signal node, by compiled node id, as of this tick. */
    private final Map<UUID, Long> values = new HashMap<>();
    /** Whether each edge-watching input (pulse nodes, counters, pulse gates) was on last tick. */
    private final Map<String, Boolean> wasOn = new HashMap<>();
    private final Map<UUID, Integer> pulseLeft = new HashMap<>();
    /** When each Timer started running (network ticks), its pulses counted from there. */
    private final Map<UUID, Long> timerStart = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> delays = new HashMap<>();
    /** Redstone to give a device: on every face ({@code face} null) or on one face (the connector touching it). */
    record Emit(GlobalPos device, @Nullable Direction face) {
    }

    /** Redstone the connectors currently give out, per device (and face). */
    private Map<Emit, Integer> emitting = Map.of();
    /** Connectors that gave out redstone after the last update, so one whose device went away is still put out. */
    private Set<UUID> poweredConnectors = Set.of();
    /** Nodes switched off this tick (by a gate, or their sheet's switch), and nodes whose pulse gate fired this tick. */
    private final Set<UUID> off = new HashSet<>();
    /** Sheet nodes that were off at the end of last tick: the logic in their sheets sleeps this tick. */
    private Set<UUID> asleep = Set.of();
    /** Device nodes gated off as of last tick: their redstone outputs give nothing, as when switched off. */
    private Set<UUID> gatedOff = Set.of();
    private final Set<UUID> pulsed = new HashSet<>();
    /**
     * Sheet and instance nodes back on this tick after sleeping: their routes wait one more tick, while the logic inside
     * wakes and reads afresh (it gave what it held while off this tick), so nothing moves on an old reading.
     */
    private final Set<UUID> waking = new HashSet<>();
    /** Alarms whose test passes, and since which tick (to wait out their delay). */
    private final Map<UUID, Long> alarmSince = new HashMap<>();
    /** An alarm node that has gone off: where it is compiled, its settings and when (wall clock, ms) it went off. */
    record Raised(CompiledSheet sheet, CompiledSheet.SignalNode node, NodeSettings.AlarmSettings alarm, long at) {
    }

    /** The alarms going off, by compiled node id, in the order they went off. */
    private final Map<UUID, Raised> raised = new java.util.LinkedHashMap<>();

    Map<UUID, Long> values() {
        return values;
    }

    Set<UUID> off() {
        return off;
    }

    Set<UUID> pulsed() {
        return pulsed;
    }

    java.util.Collection<Raised> raised() {
        return raised.values();
    }

    /** Not running (its controller unloaded or gone): nothing is known to be going off. */
    void forgetAlarms() {
        raised.clear();
        alarmSince.clear();
    }

    /** Drops edge, pulse, delay and timer memory of nodes no longer compiled (string keys start with the node id). */
    void forgetGone(Set<UUID> present) {
        java.util.function.Predicate<UUID> gone = id -> !present.contains(id);
        pulseLeft.keySet().removeIf(gone);
        timerStart.keySet().removeIf(gone);
        delays.keySet().removeIf(gone);
        wasOn.keySet().removeIf(key -> {
            int slash = key.indexOf('/');
            String head = slash < 0 ? key : key.substring(0, slash);
            try {
                return gone.test(UUID.fromString(head));
            } catch (IllegalArgumentException notAnId) {
                return false;
            }
        });
    }

    /** Held off (redstone at the controller): no device counts as gated off. */
    void forgetGates() {
        gatedOff = Set.of();
    }

    void startTick() {
        off.clear();
        pulsed.clear();
        waking.clear();
    }

    // ---------------------------------------------------------------- reading the world

    /** Redstone at the controller block. */
    static int atController(MinecraftServer server, Network network) {
        return network.controller() == null ? 0 : received(server, network.controller());
    }

    /** The signal a block receives from its neighbors. */
    static int received(MinecraftServer server, GlobalPos at) {
        ServerLevel level = server.getLevel(at.dimension());
        // The neighbors' chunks too: reading a block in an unloaded chunk would load it.
        return level != null && level.isAreaLoaded(at.pos(), 1) ? level.getBestNeighborSignal(at.pos()) : 0;
    }

    /**
     * The signal a device gives out of one face (or its strongest face), or its comparator value. Only devices of the
     * network are read; a sheet (an imported one, say) naming any other block reads 0.
     */
    static int givenOut(MinecraftServer server, Network network, GlobalPos device, Optional<FaceKey> face, boolean comparator) {
        ServerLevel level = server.getLevel(device.dimension());
        BlockPos pos = device.pos();
        // Its neighbors too: a block's signal can read theirs (a solid block powered through it), loading their chunk.
        if (network.device(device) == null || level == null || !level.isAreaLoaded(pos, 1)) {
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

    /**
     * Works out every signal node of the running sheets, then applies Redstone out nodes to the connectors.
     *
     * <p>{@code dormant} sheets belong to a network the controller's redstone has stopped: their signal nodes are
     * still worked out (latches and counters keep counting), but their Redstone out nodes give nothing.
     */
    void evaluate(NetworkRuntime.Context ctx, List<CompiledSheet> sheets, List<CompiledSheet> dormant) {
        Map<Emit, Integer> emit = new HashMap<>();
        Map<Emit, Integer> dark = new HashMap<>();
        Set<UUID> worked = new HashSet<>();
        for (List<CompiledSheet> group : List.of(sheets, dormant)) {
            for (CompiledSheet sheet : group) {
                // Switched-off sheets: their own switch, or a gate or redstone on their node as of last tick.
                Set<UUID> sleeping = new HashSet<>(sheet.disabledHolders());
                sleeping.addAll(asleep);
                Set<UUID> slept = new HashSet<>();
                for (CompiledSheet.SignalNode node : sheet.signals()) {
                    UUID id = node.node().id();
                    boolean sleepsNow = !sheet.keepRunning().contains(id) && sheet.inside(id, sleeping);
                    // Awake: what an output held while off is no longer kept (written only when there was one).
                    if (!sleepsNow && sheet.whileOff(id) != null) {
                        store(ctx, id + "/held", 0);
                    }
                    if (sleepsNow) {
                        values.put(id, node.node().settings() instanceof NodeSettings.AlarmSettings ? 0 : whileOff(ctx, sheet, node, group == sheets ? emit : dark));
                        // A sleeping Sequencer's tracks keep their last values, like any switched-off output.
                        if (node.node().settings() instanceof NodeSettings.SequencerSettings asleepSequencer) {
                            for (int track = 1; track <= asleepSequencer.tracks().size(); track++) {
                                worked.add(CompiledSheet.outputKey(id, track));
                            }
                        }
                        slept.add(id);
                        asleepNow.add(id);
                    } else if (node.node().settings() instanceof NodeSettings.RedstoneOutSettings && sheet.inside(id, gatedOff)) {
                        // Gives nothing while its device is gated off. Its redstone readings go on: one may be what
                        // turns the gate back on (a chest's comparator gating its own device).
                        values.put(id, 0L);
                        slept.add(id);
                    } else if (node.node().settings() instanceof NodeSettings.AlarmSettings alarm) {
                        values.put(id, alarm(ctx, sheet, node, alarm));
                    } else if (node.node().settings() instanceof NodeSettings.SequencerSettings sequencer) {
                        values.put(id, sequencer(ctx, node, sequencer, worked));
                    } else {
                        values.put(id, output(ctx, sheet, node, group == sheets ? emit : dark));
                    }
                    worked.add(id);
                }
                for (CompiledSheet.SignalNode node : sheet.signals()) {
                    if (!slept.contains(node.node().id())) {
                        remember(ctx, node);
                    }
                }
            }
        }
        values.keySet().retainAll(worked);
        // A switched-off or removed alarm is no longer going off; one in a sleeping sheet is muted with it.
        Set<UUID> awake = new HashSet<>(worked);
        awake.removeAll(asleepNow);
        alarmSince.keySet().retainAll(awake);
        raised.keySet().retainAll(awake);
        asleepNow.clear();
        applyEmissions(ctx.server(), ctx.network(), emit, id -> ctx.runtime().reach(id) != NetworkRuntime.Reach.OUT_OF_RANGE);
    }

    /**
     * A signal node in a switched-off sheet: it keeps its last value (outputs set to a fixed value give that), and a
     * device's redstone input keeps giving its device what that value says.
     */
    private long whileOff(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.SignalNode node, Map<Emit, Integer> emit) {
        UUID id = node.node().id();
        NodeSettings.OffBehavior behavior = sheet.whileOff(id);
        // Held: the last value, saved with the world for an output (a sheet's output pin, a device's redstone input),
        // so a restart or the network stopping doesn't drop it to 0 while the sheet stays off.
        String heldKey = id + "/held";
        long held = values.containsKey(id) ? values.get(id) : ctx.network().signalMemory().getOrDefault(heldKey, 0L);
        boolean fixed = behavior != null && behavior.mode() == NodeSettings.WhileOff.FIXED;
        long value = fixed ? behavior.value() : held;
        if (behavior != null && !fixed) {
            store(ctx, heldKey, value);
        }
        if (node.node().settings() instanceof NodeSettings.RedstoneOutSettings out) {
            int level = (int) Math.max(0, Math.min(15, value));
            if (out.faces().isEmpty()) {
                emit.merge(new Emit(out.device(), null), level, Math::max);
            } else {
                for (FaceKey face : out.faces()) {
                    if (face.direction() != null) {
                        emit.merge(new Emit(out.device(), face.direction()), level, Math::max);
                    }
                }
            }
            return level;
        }
        return value;
    }

    /** After switching: the sheet and instance nodes now off, whose logic sleeps next tick. */
    void noteAsleep(CompiledSheet sheet) {
        // Not on a pulse gate, as for devices: that is off between pulses (one operation each), and its logic sleeping
        // then would flicker its outputs and freeze its timers.
        Set<UUID> now = new HashSet<>();
        for (UUID holder : sheet.sheetNodes().values()) {
            if (off.contains(holder) && !sheet.pulses(holder) && !waking.contains(holder)) {
                now.add(holder);
            }
        }
        for (UUID holder : sheet.instanceNodes()) {
            if (off.contains(holder) && !sheet.pulses(holder) && !waking.contains(holder)) {
                now.add(holder);
            }
        }
        asleep = now;
        Set<UUID> gated = new HashSet<>();
        for (UUID device : sheet.pinHolders()) {
            // Not a pulse gate: that is off between pulses (one operation each), which would only flicker its redstone.
            if (off.contains(device) && !sheet.pulses(device)) {
                gated.add(device);
            }
        }
        gatedOff = gated;
    }

    /**
     * A Sequencer's outputs, from where it is (remembered, so it carries on after a restart): its tracks' values at the
     * step playing (for one tick as the step starts, when pulsing), and the step (1 on) as its own value.
     */
    private long sequencer(NetworkRuntime.Context ctx, CompiledSheet.SignalNode node, NodeSettings.SequencerSettings sequencer, Set<UUID> worked) {
        UUID id = node.node().id();
        Map<String, Long> memory = ctx.network().signalMemory();
        int step = (int) (long) memory.getOrDefault(id + "/step", 0L);
        boolean fire = memory.getOrDefault(id + "/fire", 0L) > 0;
        boolean playing = memory.getOrDefault(id + "/primed", 0L) > 0 && step < sequencer.steps();
        for (int track = 0; track < sequencer.tracks().size(); track++) {
            int value = playing ? sequencer.tracks().get(track).at(step) : 0;
            UUID key = CompiledSheet.outputKey(id, track + 1);
            values.put(key, sequencer.pulse() && !fire ? 0L : value);
            worked.add(key);
        }
        return playing ? step + 1 : 0;
    }

    /** After the tick: a Sequencer moves on (or starts, stops, or goes back to the first step). */
    private static void stepSequencer(NetworkRuntime.Context ctx, CompiledSheet.SignalNode node, NodeSettings.SequencerSettings sequencer,
            boolean run, boolean reset) {
        String key = node.node().id().toString();
        Map<String, Long> memory = ctx.network().signalMemory();
        long step = memory.getOrDefault(key + "/step", 0L);
        long phase = memory.getOrDefault(key + "/phase", 0L);
        boolean primed = memory.getOrDefault(key + "/primed", 0L) > 0;
        boolean fire = false;
        // Past the end while looping (its steps were cut, or loop turned on after it finished): it starts again.
        if (!reset && primed && step >= sequencer.steps() && sequencer.loop()) {
            step = 0;
            phase = 0;
            fire = run;
        } else if (reset) {
            step = 0;
            phase = 0;
            primed = false;
        } else if (run && !primed) {
            // Starting: the first step plays now.
            primed = true;
            step = 0;
            phase = 0;
            fire = true;
        } else if (run && step < sequencer.steps()) {
            phase++;
            if (phase >= sequencer.stepTicks()) {
                phase = 0;
                step++;
                if (step >= sequencer.steps() && sequencer.loop()) {
                    step = 0;
                }
                fire = step < sequencer.steps();
            }
        }
        store(ctx, key + "/step", step);
        store(ctx, key + "/phase", phase);
        store(ctx, key + "/primed", primed ? 1 : 0);
        store(ctx, key + "/fire", fire ? 1 : 0);
    }

    /** Alarm nodes in sleeping sheets this tick. */
    private final Set<UUID> asleepNow = new HashSet<>();

    /** An alarm node: while going off (its test passing for its delay) 15 if critical, 7 a warning, else 0; notes when it went off. */
    private long alarm(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.SignalNode node, NodeSettings.AlarmSettings alarm) {
        UUID id = node.node().id();
        if (node.input(0).isEmpty() || !alarm.holds(in(node, 0))) {
            alarmSince.remove(id);
            raised.remove(id);
            return 0;
        }
        long since = alarmSince.computeIfAbsent(id, k -> ctx.runtime().ticks());
        if (ctx.runtime().ticks() - since < alarm.delay() * 20L) {
            return 0;
        }
        Raised before = raised.get(id);
        raised.put(id, new Raised(sheet, node, alarm, before == null ? System.currentTimeMillis() : before.at()));
        return alarm.severity() == NodeSettings.AlarmSeverity.CRITICAL ? ON : 7;
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

    private long output(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.SignalNode node, Map<Emit, Integer> emit) {
        UUID id = node.node().id();
        String key = id.toString();
        return switch (node.node().settings()) {
            case NodeSettings.RedstoneInSettings in -> {
                // Read only through a connector that reaches the controller (wired to it, or in antenna range).
                if (ctx.runtime().outOfRange(in.device())) {
                    yield 0;
                }
                if (in.comparator() || in.faces().isEmpty()) {
                    yield givenOut(ctx.server(), ctx.network(), in.device(), Optional.empty(), in.comparator());
                }
                int best = 0;
                for (FaceKey face : in.faces()) {
                    best = Math.max(best, givenOut(ctx.server(), ctx.network(), in.device(), Optional.of(face), false));
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
            // Its value while its "on" input is above 0 or unwired, else 0 (an on/off signal turned into a value).
            case NodeSettings.ConstantSettings constant -> node.input(0).isEmpty() || in(node, 0) > 0 ? constant.value() : 0;
            case NodeSettings.TimerSettings timer -> {
                boolean running = node.input(0).isEmpty() || in(node, 0) > 0;
                // Counted from when it starts running (a pulse then, and every interval after), not from the
                // network's clock: a short run would otherwise often see no pulse at all.
                Boolean was = wasOn.put(key + "/run", running);
                if (running && !Boolean.TRUE.equals(was)) {
                    timerStart.put(id, ctx.runtime().ticks());
                }
                long since = ctx.runtime().ticks() - timerStart.getOrDefault(id, ctx.runtime().ticks());
                yield running && Math.floorMod(since, Math.max(1, timer.interval())) == 0 ? ON : 0;
            }
            case NodeSettings.PulseSettings pulse -> {
                boolean high = in(node, 0) > 0;
                // Last tick's input saved with the world (as a Counter's is): an input held on across a restart
                // isn't taken for a new rising edge.
                Boolean before = wasOn.containsKey(key) ? wasOn.get(key) : ctx.network().signalMemory().getOrDefault(key + "/on", 0L) > 0;
                wasOn.put(key, high);
                store(ctx, key + "/on", high ? 1 : 0);
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
            // Where the dashboard set it (kept like a latch's state). On/off gives its value while on, whatever it was
            // when flipped; a level switch gives its level, no more than its value.
            case NodeSettings.SwitchSettings toggle -> {
                long set = ctx.network().signalMemory().getOrDefault(key, 0L);
                yield toggle.level() ? Math.min(toggle.value(), set) : set > 0 ? toggle.value() : 0;
            }
            case NodeSettings.CounterSettings counter -> ctx.network().signalMemory().getOrDefault(key, 0L);
            case NodeSettings.DelaySettings delay -> {
                ArrayDeque<Long> queue = delays.get(id);
                yield queue != null && queue.size() >= Math.max(1, delay.ticks()) ? queue.peekFirst() : 0;
            }
            // Sheet pins pass signals through a sheet's (or blueprint instance's) edge.
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
                Boolean before = wasOn.containsKey(key) ? wasOn.get(key) : memory.getOrDefault(key + "/on", 0L) > 0;
                wasOn.put(key, high);
                store(ctx, key + "/on", high ? 1 : 0);
                boolean rising = high && !Boolean.TRUE.equals(before);
                long count = memory.getOrDefault(key, 0L);
                store(ctx, key, in(node, 1) > 0 ? 0 : rising ? count + 1 : count);
            }
            case NodeSettings.SequencerSettings sequencer ->
                    stepSequencer(ctx, node, sequencer, node.input(0).isEmpty() || in(node, 0) > 0, in(node, 1) > 0);
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

    /** How much of a resource a device of the network holds (items, mB or FE), all of it: for charts. */
    static long amount(NetworkRuntime.Context ctx, CompiledSheet sheet, io.github.leadassimilator.reticulum.graph.ResourceKind resource, GlobalPos device) {
        return count(ctx, sheet, new NodeSettings.LevelSettings(resource, device, List.of(), ""));
    }

    /** How much of a resource a device holds (items, mB or FE), counting only what matches the node's filter. */
    private static long count(NetworkRuntime.Context ctx, CompiledSheet sheet, NodeSettings.LevelSettings level) {
        NodeSettings.Port port = new NodeSettings.Port(level.resource(), level.device(), level.faces(), Optional.empty());
        ServerLevel world = ctx.server().getLevel(level.device().dimension());
        if (world == null) {
            return 0;
        }
        Filters.Context context = Filters.Context.of(world);
        // No filter: all of it.
        String expression = level.filter();
        boolean comparator = level.output() == NodeSettings.LevelOutput.COMPARATOR;
        boolean[] any = {false};
        double fullness = 0;
        long amount = 0;
        switch (level.resource()) {
            case ITEM -> {
                Filters.ItemFilter filter = expression.isBlank() ? (stack, c) -> true : sheet.itemFilter(expression);
                ItemPort items = ctx.runtime().itemPort(ctx.server(), ctx.network(), port);
                amount = items.count(stack -> filter.test(stack, context));
                if (comparator) {
                    fullness = items.fullness(stack -> filter.test(stack, context), any);
                }
            }
            case FLUID -> {
                Filters.FluidFilter filter = expression.isBlank() ? (fluid, c) -> true : sheet.fluidFilter(expression);
                double shares = 0;
                int tanks = 0;
                for (IFluidHandler handler : ctx.runtime().fluidView(ctx.server(), ctx.network(), port)) {
                    for (int tank = 0; tank < handler.getTanks(); tank++) {
                        tanks++;
                        FluidStack fluid = handler.getFluidInTank(tank);
                        if (!fluid.isEmpty() && filter.test(fluid, context)) {
                            amount += fluid.getAmount();
                            shares += fluid.getAmount() / (double) Math.max(1, handler.getTankCapacity(tank));
                            any[0] = true;
                        }
                    }
                }
                fullness = tanks == 0 ? 0 : shares / tanks;
            }
            case ENERGY -> {
                long capacity = 0;
                for (IEnergyStorage storage : ctx.runtime().energyView(ctx.server(), ctx.network(), port)) {
                    amount += storage.getEnergyStored();
                    capacity += storage.getMaxEnergyStored();
                }
                any[0] = amount > 0;
                fullness = capacity == 0 ? 0 : (double) amount / capacity;
            }
            case REDSTONE, ME -> {
            }
            case CHEMICAL -> {
                amount = Mods.MEKANISM ? ChemicalMover.count(ctx, port, expression.isBlank() ? (chemical, c) -> true : sheet.chemicalFilter(expression), context) : 0;
                // Fullness isn't worked out for chemicals: like a comparator, that is just "any or not".
                any[0] = amount > 0;
            }
        }
        return level.value(amount, Math.min(1, fullness), any[0]);
    }

    // ---------------------------------------------------------------- redstone out

    /** Gives each connector's redstone to the devices it faces, touching only what changed. */
    /** {@code reaches}: whether a connector reaches the controller; one that doesn't gives nothing (cut off by cable, out of range). */
    void applyEmissions(MinecraftServer server, Network network, Map<Emit, Integer> emit, java.util.function.Predicate<UUID> reaches) {
        if (emit.isEmpty() && emitting.isEmpty() && poweredConnectors.isEmpty()) {
            return;
        }
        Set<GlobalPos> devices = new HashSet<>();
        emit.keySet().forEach(e -> devices.add(e.device()));
        emitting.keySet().forEach(e -> devices.add(e.device()));
        Set<UUID> powered = new HashSet<>();
        for (ConnectorRecord connector : network.connectors()) {
            // Only connectors facing a device that gets (or got) redstone, or that gave some out last time.
            if (!poweredConnectors.contains(connector.id()) && connector.devices().stream().noneMatch(devices::contains)) {
                continue;
            }
            ServerLevel level = server.getLevel(connector.pos().dimension());
            if (level == null || !level.isLoaded(connector.pos().pos())
                    || !(level.getBlockEntity(connector.pos().pos()) instanceof ConnectorBlockEntity be)) {
                continue;
            }
            Set<Direction> set = EnumSet.noneOf(Direction.class);
            boolean changed = false;
            for (GlobalPos device : connector.devices()) {
                if (!devices.contains(device) || !device.dimension().equals(connector.pos().dimension())) {
                    continue;
                }
                Direction toward = io.github.leadassimilator.reticulum.network.Devices.facing(connector.pos().pos(), device.pos());
                if (toward == null) {
                    continue;
                }
                // The device's face touching this connector is the opposite of the way the connector faces it.
                int power = !reaches.test(connector.id()) ? 0
                        : Math.max(emit.getOrDefault(new Emit(device, null), 0), emit.getOrDefault(new Emit(device, toward.getOpposite()), 0));
                set.add(toward);
                changed |= be.setPower(toward, power);
            }
            // A side still powered whose device went away (broken, or its side detached) goes dark too.
            for (Direction side : Direction.values()) {
                if (!set.contains(side) && be.power(side) > 0) {
                    changed |= be.setPower(side, 0);
                }
            }
            if (changed && level.isAreaLoaded(connector.pos().pos(), 1)) {
                level.updateNeighborsAt(connector.pos().pos(), be.getBlockState().getBlock());
            }
            for (Direction side : Direction.values()) {
                if (be.power(side) > 0) {
                    powered.add(connector.id());
                    break;
                }
            }
        }
        poweredConnectors = powered;
        emitting = emit;
    }

    /** The network stopped (switched off, or its controller went away): connectors stop giving out redstone. */
    void stop(MinecraftServer server, Network network) {
        applyEmissions(server, network, Map.of(), id -> true);
        values.clear();
        gatedOff = Set.of();
        delays.clear();
        pulseLeft.clear();
        timerStart.clear();
        // A Timer counts from when it starts running: started again, it starts again (its "running" edge forgotten).
        wasOn.keySet().removeIf(key -> key.endsWith("/run"));
    }

    // ---------------------------------------------------------------- switching

    /**
     * Works out which nodes of a sheet are off this tick: those in a switched-off sheet, and those whose gate is off. A
     * pulse gate is off except on the tick its signal rises above 0, when its node does one operation.
     */
    void switchNodes(NetworkRuntime.Context ctx, CompiledSheet sheet) {
        // Sheets switched off by their own switch: everything in them is off.
        off.addAll(sheet.disabledHolders());
        for (Map.Entry<UUID, List<UUID>> entry : sheet.switchedBy().entrySet()) {
            UUID node = entry.getKey();
            boolean high = gate(entry.getValue()) > 0;
            if (sheet.pulses(node)) {
                // Saved with the world, like a Pulse node's input: no extra operation on every restart.
                String key = node + "/gate";
                Boolean before = wasOn.containsKey(key) ? wasOn.get(key) : ctx.network().signalMemory().getOrDefault(key, 0L) > 0;
                wasOn.put(key, high);
                store(ctx, key, high ? 1 : 0);
                if (high && !Boolean.TRUE.equals(before)) {
                    pulsed.add(node);
                } else {
                    off.add(node);
                }
            } else if (!high) {
                off.add(node);
            }
        }
        // Asleep last tick, on now: off one tick more (see waking).
        for (UUID holder : asleep) {
            if (!off.contains(holder) && (sheet.sheetNodes().containsValue(holder) || sheet.instanceNodes().contains(holder))) {
                waking.add(holder);
                off.add(holder);
            }
        }
    }
}
