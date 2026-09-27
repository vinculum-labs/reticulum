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
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
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
    private long ticks;

    private record CacheKey(GlobalPos device, FaceKey face, ResourceKind resource) {
    }

    public NetworkRuntime(UUID networkId) {
        this.networkId = networkId;
    }

    public long ticks() {
        return ticks;
    }

    public Metrics metrics() {
        return metrics;
    }

    /** The compiled form of a sheet, if the engine has compiled it (sheets are compiled while the network runs). */
    @Nullable
    public CompiledSheet compiledIfPresent(UUID sheetId) {
        return compiled.get(sheetId);
    }

    // ---------------------------------------------------------------- ticking

    public void tick(MinecraftServer server, Network network) {
        ticks++;
        refillTokens(network);
        List<CompiledSheet> sheets = new ArrayList<>();
        for (Sheet sheet : network.sheets()) {
            if (sheet.enabled()) {
                sheets.add(compiled(sheet, network));
            }
        }
        sheets.sort(Comparator.comparingInt((CompiledSheet c) -> c.sheet().priority()).reversed());
        Context context = new Context(server, network, this);
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

    public CompiledSheet compiled(Sheet sheet, Network network) {
        CompiledSheet current = compiled.get(sheet.id());
        if (current == null || !current.isCurrent(sheet, network.filters())) {
            current = CompiledSheet.compile(sheet, network.filters());
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

    private void refillTokens(Network network) {
        for (Sheet sheet : network.sheets()) {
            for (Node node : sheet.nodes()) {
                if (node.settings() instanceof NodeSettings.RateLimitSettings rate) {
                    double perTick = rate.perSecond() / 20.0;
                    // Bursts are limited to one second's worth.
                    double capacity = Math.max(rate.perSecond(), perTick);
                    tokens.merge(node.id(), perTick, (a, b) -> Math.min(capacity, a + b));
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
        metrics.record(route, amount);
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

    public List<IFluidHandler> fluids(MinecraftServer server, Network network, NodeSettings.Port port) {
        return handlers(server, network, port, Capabilities.FluidHandler.BLOCK, ResourceKind.FLUID);
    }

    public List<IEnergyStorage> energy(MinecraftServer server, Network network, NodeSettings.Port port) {
        return handlers(server, network, port, Capabilities.EnergyStorage.BLOCK, ResourceKind.ENERGY);
    }

    /**
     * Resolves a port. With no faces chosen, the unsided handler is used when the block has one (it sees the whole
     * inventory once); otherwise every face that answers. Equal handlers are listed once.
     */
    private <T> List<T> handlers(MinecraftServer server, Network network, NodeSettings.Port port, BlockCapability<T, Direction> capability,
            ResourceKind resource) {
        GlobalPos device = port.device();
        DeviceRecord record = network.device(device);
        ServerLevel level = server.getLevel(device.dimension());
        if (record == null || record.state() != DeviceRecord.State.ACTIVE || level == null || !level.isLoaded(device.pos())) {
            return List.of();
        }
        if (port.faces().isEmpty()) {
            T unsided = capability(level, device, FaceKey.UNSIDED, capability, resource);
            if (unsided != null) {
                return List.of(unsided);
            }
        }
        Set<T> found = new LinkedHashSet<>();
        for (FaceKey face : port.faces().isEmpty() ? List.of(FaceKey.values()) : port.faces()) {
            T handler = capability(level, device, face, capability, resource);
            if (handler != null) {
                found.add(handler);
            }
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
