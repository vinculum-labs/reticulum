package io.github.leadassimilator.reticulum.engine;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.server.MinecraftServer;

/**
 * Keeps AE2 grid connections matching the ME link wires of a network's running sheets. Implemented by the AE2
 * integration, so the engine never loads AE2 classes.
 */
public interface MeLinker {
    /**
     * Connects every wanted link whose two endpoints exist and are in reach, and disconnects the rest.
     *
     * @param inReach whether a connector reaches the controller (by cable, or within antenna range)
     */
    void update(MinecraftServer server, Network network, Collection<CompiledSheet.MeLink> wanted, java.util.function.Predicate<UUID> inReach);

    /** Disconnects everything, e.g. when the network stops. */
    void clear();

    /** Channel use of each ME pin, by pin ("node/out/0"). */
    Map<String, Usage> usage(MinecraftServer server, Network network, Collection<CompiledSheet.MeLink> wanted);

    /**
     * {@code through}: channels going through this port (the AE2 machines behind it). {@code used} of {@code max}:
     * the fullest cable on the way to the ME controller, which every port on that way shares. Not connected: all 0
     * and {@code connected} false.
     */
    record Usage(boolean connected, int through, int used, int max) {
        public static final Usage NOT_CONNECTED = new Usage(false, 0, 0, 0);
    }
}
