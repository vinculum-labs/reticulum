package io.github.leadassimilator.reticulum.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runs every network once per server tick, after the world has ticked. */
public final class NetworkTicker {
    private static final Map<UUID, NetworkRuntime> RUNTIMES = new HashMap<>();

    private NetworkTicker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Network network : NetworkManager.data(server).networks()) {
            if (!isRunning(server, network)) {
                NetworkRuntime idle = RUNTIMES.get(network.id());
                if (idle != null) {
                    idle.stop(server, network);
                }
                continue;
            }
            try {
                NetworkRuntime runtime = runtime(network.id());
                long start = System.nanoTime();
                runtime.tick(server, network);
                runtime.recordTickTime(System.nanoTime() - start);
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                // One broken network (or a misbehaving machine, or an optional mod whose internals changed) must not
                // take the server down. Other errors (out of memory) are left to crash it as usual.
                Reticulum.LOGGER.error("Reticulum network {} ({}) failed this tick", network.name(), network.id(), e);
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        RUNTIMES.clear();
    }

    /**
     * Tags (and recipes, for is:smeltable) can change with /reload: sheets are compiled again, so their filters
     * don't keep answers worked out from the old ones.
     */
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            RUNTIMES.values().forEach(NetworkRuntime::forgetCompiled);
        }
    }

    /** Drops a deleted (or merged-away) network's runtime, stopping it first so its AE2 links and redstone end too. */
    public static void forget(MinecraftServer server, Network network) {
        NetworkRuntime runtime = RUNTIMES.remove(network.id());
        if (runtime != null) {
            runtime.stop(server, network);
        }
    }

    public static NetworkRuntime runtime(UUID networkId) {
        return RUNTIMES.computeIfAbsent(networkId, NetworkRuntime::new);
    }

    /** A network runs while it is switched on and its controller is placed and loaded. */
    public static boolean isRunning(MinecraftServer server, Network network) {
        return network.enabled() && NetworkManager.controller(server, network) != null;
    }
}
