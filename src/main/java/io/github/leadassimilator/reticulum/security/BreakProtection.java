package io.github.leadassimilator.reticulum.security;

import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/** Stops non-members from breaking the cables, connectors and controller of a Private network. */
public final class BreakProtection {
    private BreakProtection() {
    }

    public static void onBreak(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Network network = Topology.network(level, event.getPos());
        if (network != null && !Security.canBreak(network, event.getPlayer())) {
            event.getPlayer().sendOverlayMessage(Security.canView(network, event.getPlayer())
                    ? Component.translatable("message.reticulum.protected", network.name())
                    : Component.translatable("message.reticulum.protected_private"));
            event.setCanceled(true);
        }
    }
}
