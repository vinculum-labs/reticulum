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
            } catch (RuntimeException e) {
                // One broken network (or a misbehaving machine) must not take the server down.
                Reticulum.LOGGER.error("Reticulum network {} ({}) failed this tick", network.name(), network.id(), e);
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        RUNTIMES.clear();
    }

    public static void forget(UUID networkId) {
        RUNTIMES.remove(networkId);
    }

    public static NetworkRuntime runtime(UUID networkId) {
        return RUNTIMES.computeIfAbsent(networkId, NetworkRuntime::new);
    }

    /** A network runs while it is switched on and its controller is placed and loaded. */
    public static boolean isRunning(MinecraftServer server, Network network) {
        GlobalPos controller = network.controller();
        if (!network.enabled() || controller == null) {
            return false;
        }
        ServerLevel level = server.getLevel(controller.dimension());
        return level != null && level.isLoaded(controller.pos())
                && level.getBlockEntity(controller.pos()) instanceof ControllerBlockEntity be && network.id().equals(be.networkId());
    }
}
