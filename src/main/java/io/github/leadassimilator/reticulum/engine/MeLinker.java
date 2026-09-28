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

    /** Channels in use and available on each ME port node, by node id, as "27/32". */
    Map<UUID, String> usage(MinecraftServer server, Network network, Collection<CompiledSheet.MeLink> wanted);
}
