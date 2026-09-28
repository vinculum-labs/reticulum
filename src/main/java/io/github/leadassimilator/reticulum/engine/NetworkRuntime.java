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
    /** Sheets whose redstone pulse (or the network's) fired this tick: their item flows do one operation now. */
    private final Set<UUID> pulsedSheets = new java.util.HashSet<>();
    /** Sheets switched off by redstone this tick, for the editor. */
    private final Set<UUID> sheetsOff = new java.util.HashSet<>();
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

    private long reachVersion = -1;
    private long reachWorkedOut = Long.MIN_VALUE;
    private int reachTier = -1;
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

    /** The compiled form of a sheet, if the engine has compiled it (sheets are compiled while the network runs). */
    @Nullable
    public CompiledSheet compiledIfPresent(UUID sheetId) {
        return compiled.get(sheetId);
    }

    // ---------------------------------------------------------------- ticking

    public void tick(MinecraftServer server, Network network) {
        ticks++;
        signals.startTick();
        statuses.values().removeIf(status -> status == Status.SWITCHED_OFF);
        pulsedSheets.clear();
        sheetsOff.clear();
        Signals.Verdict networkVerdict = signals.check(server, network, "network", network.switches().redstone(), null);
        if (networkVerdict == Signals.Verdict.OFF) {
            stop(server, network);
            return;
        }
        stopped = false;
        updateReach(server, network);
        List<CompiledSheet> sheets = new ArrayList<>();
        for (Sheet sheet : network.sheets()) {
            if (!network.runs(sheet)) {
                continue;
            }
            Signals.Verdict verdict = sheetVerdict(server, network, sheet);
            if (verdict == Signals.Verdict.OFF) {
                sheetsOff.add(sheet.id());
                continue;
            }
            sheets.add(compiled(sheet, network));
            if (verdict == Signals.Verdict.PULSED || networkVerdict == Signals.Verdict.PULSED) {
                pulsedSheets.add(sheet.id());
            }
        }
        Context context = new Context(server, network, this);
        signals.evaluate(context, sheets);
        for (CompiledSheet sheet : sheets) {
            if (sheet.switchable()) {
                signals.switchNodes(server, network, sheet);
                for (UUID node : signals.off()) {
                    if (sheet.conditions().containsKey(node) || sheet.switchedBy().containsKey(node)) {
                        statuses.put(sheet.owner(node), Status.SWITCHED_OFF);
                    }
                }
            }
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

    /** The network isn't running: connectors stop giving out redstone, once. */
    public void stop(MinecraftServer server, Network network) {
        if (!stopped) {
            signals.stop(server, network);
            stopped = true;
        }
    }

    // ---------------------------------------------------------------- wireless reach

    /**
     * Works out which connectors a cable joins to the controller and which the antenna must reach. Done again
     * whenever cables change anywhere, and every 5 seconds for chunks loading and unloading.
     */
    private void updateReach(MinecraftServer server, Network network) {
        GlobalPos controller = network.controller();
        ServerLevel level = controller == null ? null : server.getLevel(controller.dimension());
        if (level == null) {
            return;
        }
        int tier = level.getBlockEntity(controller.pos()) instanceof ControllerBlockEntity c ? c.antennaTier() : 0;
        if (reachVersion == Topology.version() && reachTier == tier && ticks - reachWorkedOut < 100) {
            return;
        }
        reachVersion = Topology.version();
        reachTier = tier;
        reachWorkedOut = ticks;
        Set<net.minecraft.core.BlockPos> wired = Topology.component(level, controller.pos(), null).blocks();
        Map<UUID, Reach> found = new HashMap<>();
        Set<GlobalPos> reached = new java.util.HashSet<>();
        Set<GlobalPos> all = new java.util.HashSet<>();
        for (ConnectorRecord connector : network.connectors()) {
            Reach r = connector.pos().dimension().equals(controller.dimension()) && wired.contains(connector.pos().pos()) ? Reach.WIRED
                    : inRange(tier, controller, connector.pos()) ? Reach.WIRELESS : Reach.OUT_OF_RANGE;
            found.put(connector.id(), r);
            all.addAll(connector.devices());
            if (r != Reach.OUT_OF_RANGE) {
                reached.addAll(connector.devices());
            }
        }
        all.removeAll(reached);
        reach = found;
        unreachable = all;
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

    /** A sheet runs under its own redstone condition and those of every folder it is in. */
    private Signals.Verdict sheetVerdict(MinecraftServer server, Network network, Sheet sheet) {
        Signals.Verdict verdict = signals.check(server, network, "sheet:" + sheet.id(), sheet.redstone(), null);
        String folder = sheet.folder();
        while (!folder.isEmpty() && verdict != Signals.Verdict.OFF) {
            Redstone redstone = network.switches().folderRedstone().get(folder);
            if (redstone != null) {
                Signals.Verdict parent = signals.check(server, network, "folder:" + folder, redstone, null);
                verdict = parent == Signals.Verdict.OFF ? parent : parent == Signals.Verdict.PULSED ? parent : verdict;
            }
            int slash = folder.lastIndexOf('/');
            folder = slash < 0 ? "" : folder.substring(0, slash);
        }
        return verdict;
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
        if (pulsedSheets.contains(sheet.sheet().id())) {
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

    public CompiledSheet compiled(Sheet sheet, Network network) {
        CompiledSheet current = compiled.get(sheet.id());
        if (current == null || !current.isCurrent(sheet, network)) {
            current = CompiledSheet.compile(sheet, network);
            compiled.put(sheet.id(), current);
            for (String error : current.errors()) {
                if (reportedErrors.add(sheet.id() + error)) {
                    Reticulum.LOGGER.warn("Network {} sheet '{}': {}", networkId, sheet.name(), error);
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
            int offset = branch.mode() == NodeSettings.SplitMode.ROUND_ROBIN ? roundRobin.getOrDefault(branch.node(), 0) : 0;
            key[i] = Math.floorMod(branch.index() - offset, branch.count());
        }
        return key;
    }

    public List<CompiledSheet.Route> ordered(List<CompiledSheet.Route> routes) {
        List<CompiledSheet.Route> sorted = new ArrayList<>(routes);
        sorted.sort((a, b) -> java.util.Arrays.compare(branchKey(a), branchKey(b)));
        return sorted;
    }

    /** After a delivery, every round-robin branch on the route moves on to the next output. */
    public void advance(CompiledSheet.Route route) {
        for (CompiledSheet.Branch branch : route.branches()) {
            if (branch.mode() == NodeSettings.SplitMode.ROUND_ROBIN) {
                roundRobin.put(branch.node(), Math.floorMod(branch.index() + 1, branch.count()));
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
