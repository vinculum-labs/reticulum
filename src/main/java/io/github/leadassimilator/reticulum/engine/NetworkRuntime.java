package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Redstone;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Everything the engine keeps for one network between ticks and never saves: compiled sheets, capability caches,
 * rate-limit tokens and round-robin positions.
 */
public final class NetworkRuntime {
    private final UUID networkId;
    private final Map<UUID, CompiledSheet> compiled = new HashMap<>();
    private final Map<CacheKey, BlockCapabilityCache<?, Direction>> caches = new HashMap<>();
    private final Map<UUID, Double> tokens = new HashMap<>();
    private final Map<UUID, Integer> roundRobin = new HashMap<>();
    private final Map<UUID, Integer> backoff = new HashMap<>();
    private final Set<String> reportedErrors = new LinkedHashSet<>();
    private final Metrics metrics = new Metrics();
    /** Why each node last moved nothing; absent once it moves again. Keyed by the node the editor shows. */
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Signals signals = new Signals();
    /** The network's redstone pulse fired this tick: every item flow does one operation now. */
    private boolean networkPulsed;
    /** Sheets switched off this tick by a gate or redstone (theirs or a sheet's above them), for the editor. */
    private final Set<UUID> sheetsOff = new java.util.HashSet<>();
    /** Sub-sheets compiled on their own, only to show their problems in the editor (they never run on their own). */
    private final Map<UUID, CompiledSheet> templates = new HashMap<>();
    private boolean stopped = true;
    private long ticks;

    /** How each connector reaches the controller. */
    public enum Reach {
        /** A cable path joins it to the controller. */
        WIRED,
        /** No cable joins it, but the controller's antenna reaches it. */
        WIRELESS,
        /** No cable joins it and the antenna doesn't reach it (or there is none): its devices are offline. */
        OUT_OF_RANGE;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** AE2 links, when AE2 is installed. */
    @Nullable
    private final MeLinker meLinker = io.github.leadassimilator.reticulum.integration.Mods.AE2
            ? io.github.leadassimilator.reticulum.integration.ae2.Ae2Links.create() : null;
    private Map<String, MeLinker.Usage> meUsage = Map.of();

    // ---------------------------------------------------------------- power (see Power; everything is free by default)

    private Power power = Power.FREE;
    private boolean powered = true;
    @Nullable
    private ControllerBlockEntity powerSource;
    /** FE owed but not yet paid, so fractional costs add up exactly. */
    private double upkeepOwed;
    private double operationsOwed;
    private double lastUpkeep;
    /** Wireless connectors by what they cost: count per antenna tier, total distance, other dimensions. */
    private int wirelessConnectors;
    private double wirelessDistance;
    private int crossDimensionConnectors;

    private long reachVersion = -1;
    private int reachTier = -1;
    /** Where the rolling connector rescan got to. */
    private int rescanCursor;
    private Map<UUID, Reach> reach = Map.of();
    /** Devices that only out-of-range connectors reach. */
    private Set<GlobalPos> unreachable = Set.of();

    private record CacheKey(GlobalPos device, FaceKey face, ResourceKind resource) {
    }

    private static final List<FaceKey> SIDES = List.of(FaceKey.DOWN, FaceKey.UP, FaceKey.NORTH, FaceKey.SOUTH, FaceKey.WEST, FaceKey.EAST);

    public NetworkRuntime(UUID networkId) {
        this.networkId = networkId;
    }

    public long ticks() {
        return ticks;
    }

    /** How long this network's last ticks took, in nanoseconds (a rolling window of the last 200). */
    private final long[] tickTimes = new long[200];
    private int tickTimeCount;

    void recordTickTime(long nanos) {
        tickTimes[tickTimeCount++ % tickTimes.length] = nanos;
        lastTickTime = nanos;
    }

    private long lastTickTime;

    /** How long the last tick took, in nanoseconds. */
    public long lastTickTime() {
        return lastTickTime;
    }

    /** Average and longest tick over the last 200 ticks (or fewer), in nanoseconds. */
    public long[] tickTimeStats() {
        int n = Math.min(tickTimeCount, tickTimes.length);
        long sum = 0;
        long max = 0;
        for (int i = 0; i < n; i++) {
            sum += tickTimes[i];
            max = Math.max(max, tickTimes[i]);
        }
        return new long[] {n == 0 ? 0 : sum / n, max};
    }

    public Metrics metrics() {
        return metrics;
    }

    public Map<UUID, Status> statuses() {
        return statuses;
    }

    /** Records the outcome of an attempt on a node: a reason it moved nothing, or null once it moved something. */
    void status(UUID node, @Nullable Status status) {
        if (status == null) {
            statuses.remove(node);
        } else {
            statuses.put(node, status);
        }
    }

    /** Drops every compiled sheet, so they're compiled again on the next tick. */
    void forgetCompiled() {
        compiled.clear();
    }

    /** The network as compiled, if the engine has compiled it (it is compiled while the network runs). */
    @Nullable
    public CompiledSheet compiledIfPresent() {
        return compiled.values().stream().findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- ticking

    public void tick(MinecraftServer server, Network network) {
        ticks++;
        if (network.ensureTree()) {
            NetworkManager.changed(server, network);
        }
        signals.startTick();
        statuses.values().removeIf(status -> status == Status.SWITCHED_OFF);
        sheetsOff.clear();
        Signals.Verdict networkVerdict = signals.check(server, network, "network", network.switches().redstone(), null);
        if (networkVerdict == Signals.Verdict.OFF) {
            dormant(server, network);
            return;
        }
        networkPulsed = networkVerdict == Signals.Verdict.PULSED;
        stopped = false;
        updateReach(server, network);
        payUpkeep(server, network);
        // The whole tree compiles as one: every sheet that runs is in it, and sheet nodes switch what is inside them.
        List<CompiledSheet> sheets = new ArrayList<>(List.of(compiled(network.root(), network)));
        Context context = new Context(server, network, this);
        signals.evaluate(context, sheets, List.of());
        if (meLinker != null) {
            List<CompiledSheet.MeLink> links = new ArrayList<>();
            sheets.forEach(sheet -> links.addAll(sheet.meLinks()));
            meLinker.update(server, network, links, id -> reach(id) != Reach.OUT_OF_RANGE);
            if (ticks % 20 == 0) {
                meUsage = links.isEmpty() ? Map.of() : meLinker.usage(server, network, links);
            }
        }
        for (CompiledSheet sheet : sheets) {
            if (sheet.switchable()) {
                signals.switchNodes(server, network, sheet);
                for (UUID node : signals.off()) {
                    if (sheet.conditions().containsKey(node) || sheet.switchedBy().containsKey(node)) {
                        statuses.put(sheet.owner(node), Status.SWITCHED_OFF);
                    }
                }
            }
            signals.noteAsleep(sheet);
            noteSheetsOff(sheet);
        }
        refillTokens(sheets);
        sheets.sort(Comparator.comparingInt((CompiledSheet c) -> c.sheet().priority()).reversed());
        // Continuous flows go first so the tick budget can never delay them.
        for (CompiledSheet sheet : sheets) {
            ContinuousMover.run(context, sheet);
        }
        for (CompiledSheet sheet : sheets) {
            ItemMover.run(context, sheet);
        }
        if (ticks % 20 == 0) {
            Overflow.retry(context, sheets);
        }
        metrics.tick(ticks);
    }

    /**
     * The network's redstone condition has it off: nothing moves and connectors give out no redstone, but its sheets'
     * signal logic keeps working, so a condition reading a signal they send (a sensor sheet watching a battery) can
     * turn the network on again.
     */
    private void dormant(MinecraftServer server, Network network) {
        if (meLinker != null) {
            meLinker.clear();
        }
        CompiledSheet compiled = compiled(network.root(), network);
        sheetsOff.add(network.root().id());
        sheetsOff.addAll(compiled.sheetNodes().keySet());
        signals.evaluate(new Context(server, network, this), List.of(), List.of(compiled));
    }

    /** Sheets whose sheet node, or a sheet node above it, a gate or redstone switched off this tick. */
    private void noteSheetsOff(CompiledSheet compiled) {
        if (signals.off().isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : compiled.sheetNodes().entrySet()) {
            UUID node = entry.getValue();
            for (int depth = 0; node != null && depth < 256; depth++) {
                if (signals.off().contains(node)) {
                    sheetsOff.add(entry.getKey());
                    break;
                }
                node = compiled.containerOf(node);
            }
        }
    }

    /** The network isn't running: connectors stop giving out redstone, once. */
    public void stop(MinecraftServer server, Network network) {
        if (!stopped) {
            signals.stop(server, network);
            if (meLinker != null) {
                meLinker.clear();
            }
            stopped = true;
        }
    }

    // ---------------------------------------------------------------- power

    /**
     * Takes FE from sources next to the controller, then pays this tick's upkeep. When the controller can't pay it, the
     * network is unpowered: only energy flows run (so it can recover), everything else waits.
     */
    private void payUpkeep(MinecraftServer server, Network network) {
        power = Power.current();
        powered = true;
        powerSource = null;
        lastUpkeep = 0;
        if (!power.enabled()) {
            return;
        }
        ControllerBlockEntity controller = NetworkManager.controller(server, network);
        if (controller == null) {
            powered = false;
            return;
        }
        powerSource = controller;
        pullFromNeighbors((ServerLevel) controller.getLevel(), controller.getBlockPos(), controller);
        int tier = controller.antennaTier();
        lastUpkeep = power.base() + power.perConnector() * network.connectors().size()
                + wirelessConnectors * (tier <= 1 ? power.wirelessTier1() : power.wirelessTier2())
                + wirelessDistance * power.perBlock() + crossDimensionConnectors * power.crossDimension();
        upkeepOwed += lastUpkeep;
        long whole = (long) upkeepOwed;
        if (controller.draw(whole)) {
            upkeepOwed -= whole;
        } else {
            // Not charged while unpowered, so the buffer can fill up again.
            upkeepOwed -= whole;
            powered = false;
        }
    }

    /** The controller pulls FE from anything next to it that gives it (generators, batteries, other mods' cables). */
    private static void pullFromNeighbors(ServerLevel level, net.minecraft.core.BlockPos pos, ControllerBlockEntity controller) {
        for (Direction side : Direction.values()) {
            long room = ControllerBlockEntity.capacity() - controller.energy();
            if (room <= 0) {
                return;
            }
            net.minecraft.core.BlockPos next = pos.relative(side);
            if (!level.isLoaded(next) || level.getBlockEntity(next) instanceof io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity) {
                continue;
            }
            IEnergyStorage source = level.getCapability(Capabilities.EnergyStorage.BLOCK, next, side.getOpposite());
            if (source == null || !source.canExtract()) {
                continue;
            }
            int offered = source.extractEnergy((int) Math.min(room, Integer.MAX_VALUE), true);
            if (offered > 0) {
                int taken = source.extractEnergy(offered, false);
                controller.energyStorage().receiveEnergy(taken, false);
            }
        }
    }

    /** Whether the network has power this tick (always, when power is free). */
    public boolean powered() {
        return powered;
    }

    /** Whether an item operation may run: power is free, or the controller has what one costs. */
    boolean canAffordOperation() {
        return !power.enabled() || powered && powerSource != null && powerSource.energy() >= Math.ceil(power.perOperation() + operationsOwed);
    }

    /** Pays for an item operation that moved something. */
    void chargeOperation() {
        if (!power.enabled() || powerSource == null || power.perOperation() <= 0) {
            return;
        }
        operationsOwed += power.perOperation();
        long whole = (long) operationsOwed;
        if (powerSource.draw(whole)) {
            operationsOwed -= whole;
        }
    }

    /** For the editor and the controller screen: whether power costs anything, what is stored and what upkeep costs per tick. */
    public PowerState powerState(MinecraftServer server, Network network) {
        ControllerBlockEntity controller = NetworkManager.controller(server, network);
        long stored = controller == null ? 0 : controller.energy();
        return new PowerState(Power.current().enabled(), powered, stored, ControllerBlockEntity.capacity(), lastUpkeep);
    }

    public record PowerState(boolean enabled, boolean powered, long stored, long capacity, double upkeep) {
    }

    // ---------------------------------------------------------------- wireless reach

    /**
     * Works out which connectors a cable joins to the controller and which the antenna must reach. Done again only
     * when something changes: cables or joints, the antenna, or a chunk with network blocks loading or unloading.
     */
    private void updateReach(MinecraftServer server, Network network) {
        GlobalPos controller = network.controller();
        ServerLevel level = controller == null ? null : server.getLevel(controller.dimension());
        if (level == null) {
            return;
        }
        ControllerBlockEntity loaded = NetworkManager.controller(server, network);
        int tier = loaded == null ? 0 : loaded.antennaTier();
        rescanSomeConnectors(server, network);
        if (reachVersion == Topology.version(network.id()) && reachTier == tier) {
            return;
        }
        reachVersion = Topology.version(network.id());
        reachTier = tier;
        Set<net.minecraft.core.BlockPos> wired = Topology.component(level, controller.pos(), null).blocks();
        Map<UUID, Reach> found = new HashMap<>();
        wirelessConnectors = 0;
        wirelessDistance = 0;
        crossDimensionConnectors = 0;
        Set<GlobalPos> reached = new java.util.HashSet<>();
        Set<GlobalPos> all = new java.util.HashSet<>();
        for (ConnectorRecord connector : network.connectors()) {
            Reach r = connector.pos().dimension().equals(controller.dimension()) && wired.contains(connector.pos().pos()) ? Reach.WIRED
                    : inRange(tier, controller, connector.pos()) ? Reach.WIRELESS : Reach.OUT_OF_RANGE;
            found.put(connector.id(), r);
            if (r == Reach.WIRELESS) {
                wirelessConnectors++;
                if (connector.pos().dimension().equals(controller.dimension())) {
                    wirelessDistance += Math.sqrt(controller.pos().distSqr(connector.pos().pos()));
                } else {
                    crossDimensionConnectors++;
                }
            }
            all.addAll(connector.devices());
            if (r != Reach.OUT_OF_RANGE) {
                reached.addAll(connector.devices());
            }
        }
        all.removeAll(reached);
        reach = found;
        unreachable = all;
    }

    /** Ticks it takes the rolling rescan to get round every connector once. */
    private static final int RESCAN_PERIOD = 100;

    /**
     * Has a few loaded connectors look at their neighbors again, so every connector is rescanned once per
     * {@link #RESCAN_PERIOD} ticks without a spike on any one tick. Changes next to a connector are normally caught as
     * they happen, but not ones that happened while it was unloaded, or blocks that only now count as devices.
     */
    private void rescanSomeConnectors(MinecraftServer server, Network network) {
        List<ConnectorRecord> all = List.copyOf(network.connectors());
        if (all.isEmpty()) {
            return;
        }
        int count = (all.size() + RESCAN_PERIOD - 1) / RESCAN_PERIOD;
        for (int i = 0; i < count; i++) {
            ConnectorRecord connector = all.get(Math.floorMod(rescanCursor++, all.size()));
            ServerLevel level = server.getLevel(connector.pos().dimension());
            if (level != null && level.isLoaded(connector.pos().pos())) {
                Topology.rescanConnector(level, connector.pos().pos());
            }
        }
    }

    /** Whether an antenna of this tier on the controller reaches a wireless connector. */
    public static boolean inRange(int tier, GlobalPos controller, GlobalPos connector) {
        boolean sameDimension = controller.dimension().equals(connector.dimension());
        return switch (tier) {
            case 0 -> false;
            case 1 -> {
                long range = ReticulumConfig.ANTENNA_RANGE.getAsInt();
                yield sameDimension && controller.pos().distSqr(connector.pos()) <= range * range;
            }
            case 2 -> sameDimension;
            default -> true;
        };
    }

    /** How a connector reaches the controller, as of the last check (null before the network has run). */
    @Nullable
    public Reach reach(UUID connector) {
        return reach.get(connector);
    }

    public boolean outOfRange(GlobalPos device) {
        return unreachable.contains(device);
    }

    /** Channel use of each ME pin ("node/out/0"), updated once a second. */
    public Map<String, MeLinker.Usage> meUsage() {
        return meUsage;
    }

    /** Signal node outputs this tick, by node id. */
    public Map<UUID, Long> signalValues() {
        return signals.values();
    }

    /** Network signals as last published. */
    public Map<String, Long> networkSignals() {
        return signals.published();
    }

    public Set<UUID> sheetsOff() {
        return sheetsOff;
    }

    // ---------------------------------------------------------------- redstone switching

    /** Whether a source is on: nothing that controls it is switched off by redstone. */
    boolean active(CompiledSheet.Source source) {
        return signals.off().isEmpty() || disjoint(source.controls(), signals.off());
    }

    /** A source's routes that redstone leaves on. */
    List<CompiledSheet.Route> routes(CompiledSheet.Source source) {
        if (signals.off().isEmpty()) {
            return source.routes();
        }
        List<CompiledSheet.Route> on = new ArrayList<>();
        for (CompiledSheet.Route route : source.routes()) {
            if (disjoint(route.controls(), signals.off())) {
                on.add(route);
            }
        }
        return on;
    }

    /** Whether a redstone pulse fired for these routes this tick, so their item operation runs now. */
    boolean forced(CompiledSheet sheet, List<CompiledSheet.Route> routes) {
        if (networkPulsed) {
            return true;
        }
        if (signals.pulsed().isEmpty()) {
            return false;
        }
        for (CompiledSheet.Route route : routes) {
            if (!disjoint(route.controls(), signals.pulsed())) {
                return true;
            }
        }
        return false;
    }

    private static boolean disjoint(Set<UUID> a, Set<UUID> b) {
        Set<UUID> small = a.size() <= b.size() ? a : b;
        Set<UUID> large = small == a ? b : a;
        for (UUID id : small) {
            if (large.contains(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The network as compiled from its root, for any sheet in the tree (they compile as one); a sub-sheet compiled on
     * its own, only so the editor can show its problems.
     */
    public CompiledSheet compiled(Sheet sheet, Network network) {
        if (sheet.subsheet()) {
            CompiledSheet current = templates.get(sheet.id());
            if (current == null || !current.isCurrent(sheet, network)) {
                current = CompiledSheet.compile(sheet, network);
                templates.put(sheet.id(), current);
            }
            return current;
        }
        Sheet root = network.root();
        CompiledSheet current = compiled.get(root.id());
        if (current == null || !current.isCurrent(root, network)) {
            compiled.clear();
            current = CompiledSheet.compile(root, network);
            compiled.put(root.id(), current);
            for (CompiledSheet.Problem error : current.errors()) {
                if (reportedErrors.add(current.sheetOf(error.node() == null ? root.id() : error.node()) + error.message())) {
                    Sheet on = network.sheet(error.node() == null ? root.id() : current.sheetOf(error.node()));
                    Reticulum.LOGGER.warn("Network {} sheet '{}': {}", networkId, on == null ? root.name() : on.name(), error.message());
                }
            }
        }
        return current;
    }

    /** Shared by the movers for one tick. */
    public record Context(MinecraftServer server, Network network, NetworkRuntime runtime) {
    }

    // ---------------------------------------------------------------- rate limits

    private void refillTokens(List<CompiledSheet> sheets) {
        Set<UUID> seen = new java.util.HashSet<>();
        for (CompiledSheet sheet : sheets) {
            for (CompiledSheet.Source source : sheet.sources()) {
                for (CompiledSheet.Route route : source.routes()) {
                    for (Node node : route.rateLimits()) {
                        if (seen.add(node.id()) && node.settings() instanceof NodeSettings.RateLimitSettings rate) {
                            double perTick = rate.perSecond() / 20.0;
                            // Bursts are limited to one second's worth.
                            double capacity = Math.max(rate.perSecond(), perTick);
                            tokens.merge(node.id(), perTick, (a, b) -> Math.min(capacity, a + b));
                        }
                    }
                }
            }
        }
    }

    /** How much may pass through all the rate limits on a route right now. */
    public long available(List<Node> rateLimits) {
        long allowed = Long.MAX_VALUE;
        for (Node node : rateLimits) {
            allowed = Math.min(allowed, (long) Math.floor(tokens.getOrDefault(node.id(), 0.0)));
        }
        return allowed;
    }

    /** Records a delivery for metrics and charges the route's rate limits. */
    public void delivered(CompiledSheet.Route route, long amount) {
        consume(route.rateLimits(), amount);
        metrics.record(route, amount, ticks);
    }

    public void consume(List<Node> rateLimits, long amount) {
        for (Node node : rateLimits) {
            tokens.merge(node.id(), -(double) amount, Double::sum);
        }
    }

    // ---------------------------------------------------------------- idle back-off

    private static final int MAX_BACKOFF = 8;

    /** How many intervals an item source or kit currently waits between operations (1 when busy, up to 8 when idle). */
    public int backoff(UUID node) {
        return backoff.getOrDefault(node, 1);
    }

    /** Doubles the wait after an operation that moved nothing; any movement snaps it back to every interval. */
    public void recordOperation(UUID node, boolean moved) {
        if (moved) {
            backoff.remove(node);
        } else {
            backoff.put(node, Math.min(MAX_BACKOFF, backoff(node) * 2));
        }
    }

    // ---------------------------------------------------------------- round robin

    /** Sort key for a route: priority branches in wire order, round-robin branches rotated by their current offset. */
    public int[] branchKey(CompiledSheet.Route route) {
        int[] key = new int[route.branches().size()];
        for (int i = 0; i < key.length; i++) {
            CompiledSheet.Branch branch = route.branches().get(i);
            key[i] = switch (branch.mode()) {
                case ROUND_ROBIN -> Math.floorMod(branch.index() - roundRobin.getOrDefault(branch.node(), 0), branch.count());
                case PRIORITY -> branch.index();
                // Smooth weighted round robin: the output with the most credit (after this round's weights) goes first.
                case WEIGHTED -> {
                    double[] credit = credits.computeIfAbsent(branch.node(), k -> new double[branch.count()]);
                    double next = (branch.index() < credit.length ? credit[branch.index()] : 0) + branch.weight();
                    // Clamped so very large credits can't wrap around and reorder the outputs.
                    yield (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(-next * 1000)));
                }
                case NEAREST -> distance(route);
            };
        }
        return key;
    }

    /** Weighted splitters' credit per output (smooth weighted round robin). */
    private final Map<UUID, double[]> credits = new HashMap<>();

    /** How far a route's sink device is from its source device, in blocks (another dimension counts as very far). */
    private static int distance(CompiledSheet.Route route) {
        GlobalPos a = route.sourceSettings().port().device();
        GlobalPos b = route.sinkSettings().port().device();
        if (!a.dimension().equals(b.dimension())) {
            return Integer.MAX_VALUE / 2;
        }
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.sqrt(a.pos().distSqr(b.pos())));
    }

    public List<CompiledSheet.Route> ordered(List<CompiledSheet.Route> routes) {
        List<CompiledSheet.Route> sorted = new ArrayList<>(routes);
        sorted.sort((a, b) -> java.util.Arrays.compare(branchKey(a), branchKey(b)));
        return sorted;
    }

    /** After a delivery, every round-robin branch on the route moves on to the next output, and weighted ones settle their credit. */
    public void advance(CompiledSheet.Route route) {
        for (CompiledSheet.Branch branch : route.branches()) {
            if (branch.mode() == NodeSettings.SplitMode.ROUND_ROBIN) {
                roundRobin.put(branch.node(), Math.floorMod(branch.index() + 1, branch.count()));
            } else if (branch.mode() == NodeSettings.SplitMode.WEIGHTED) {
                double[] credit = credits.computeIfAbsent(branch.node(), k -> new double[branch.count()]);
                if (credit.length != branch.count()) {
                    credit = new double[branch.count()];
                    credits.put(branch.node(), credit);
                }
                for (int i = 0; i < credit.length; i++) {
                    credit[i] += branch.weights()[i];
                }
                credit[branch.index()] -= branch.totalWeight();
            }
        }
    }

    // ---------------------------------------------------------------- ports

    /** The handlers of a port, or none when its device is offline (unloaded, orphaned or missing). */
    public List<IItemHandler> items(MinecraftServer server, Network network, NodeSettings.Port port) {
        return handlers(server, network, port, Capabilities.ItemHandler.BLOCK, ResourceKind.ITEM);
    }

    /** An item port: handlers to move items through, and the view to count them with. */
    ItemPort itemPort(MinecraftServer server, Network network, NodeSettings.Port port) {
        return new ItemPort(items(server, network, port), view(server, network, port, Capabilities.ItemHandler.BLOCK, ResourceKind.ITEM), port.slots());
    }

    public List<IFluidHandler> fluids(MinecraftServer server, Network network, NodeSettings.Port port) {
        return handlers(server, network, port, Capabilities.FluidHandler.BLOCK, ResourceKind.FLUID);
    }

    public List<IEnergyStorage> energy(MinecraftServer server, Network network, NodeSettings.Port port) {
        return handlers(server, network, port, Capabilities.EnergyStorage.BLOCK, ResourceKind.ENERGY);
    }

    /** Fluid handlers for counting: the unsided one when no faces are chosen, so tanks are counted once. */
    List<IFluidHandler> fluidView(MinecraftServer server, Network network, NodeSettings.Port port) {
        return view(server, network, port, Capabilities.FluidHandler.BLOCK, ResourceKind.FLUID);
    }

    List<IEnergyStorage> energyView(MinecraftServer server, Network network, NodeSettings.Port port) {
        return view(server, network, port, Capabilities.EnergyStorage.BLOCK, ResourceKind.ENERGY);
    }

    /**
     * Resolves a port for moving resources. With no faces chosen ("auto"), every face that answers is used, so the
     * block's own side configuration decides what can go in and out (a Mekanism machine's unsided handler, for
     * example, is read-only). The unsided handler is used only when no face answers. Equal handlers are listed once.
     */
    <T> List<T> handlers(MinecraftServer server, Network network, NodeSettings.Port port, BlockCapability<T, Direction> capability,
            ResourceKind resource) {
        return resolve(server, network, port, capability, resource, false);
    }

    /**
     * Resolves a port for counting (keep rules and caps). With no faces chosen, the unsided handler is preferred:
     * it sees the whole inventory exactly once, where several faces may share slots.
     */
    <T> List<T> view(MinecraftServer server, Network network, NodeSettings.Port port, BlockCapability<T, Direction> capability,
            ResourceKind resource) {
        return resolve(server, network, port, capability, resource, true);
    }

    private <T> List<T> resolve(MinecraftServer server, Network network, NodeSettings.Port port, BlockCapability<T, Direction> capability,
            ResourceKind resource, boolean preferUnsided) {
        GlobalPos device = port.device();
        DeviceRecord record = network.device(device);
        ServerLevel level = server.getLevel(device.dimension());
        if (record == null || record.state() != DeviceRecord.State.ACTIVE || level == null || !level.isLoaded(device.pos())
                || unreachable.contains(device)) {
            return List.of();
        }
        boolean auto = port.faces().isEmpty();
        T unsided = auto ? capability(level, device, FaceKey.UNSIDED, capability, resource) : null;
        if (auto && preferUnsided && unsided != null) {
            return List.of(unsided);
        }
        Set<T> found = new LinkedHashSet<>();
        for (FaceKey face : auto ? SIDES : port.faces()) {
            T handler = capability(level, device, face, capability, resource);
            if (handler != null) {
                found.add(handler);
            }
        }
        if (found.isEmpty() && unsided != null) {
            return List.of(unsided);
        }
        return List.copyOf(found);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T> T capability(ServerLevel level, GlobalPos device, FaceKey face, BlockCapability<T, Direction> capability, ResourceKind resource) {
        BlockCapabilityCache<?, Direction> cache = caches.computeIfAbsent(new CacheKey(device, face, resource),
                key -> BlockCapabilityCache.create(capability, level, device.pos(), face.direction()));
        return (T) cache.getCapability();
    }
}
