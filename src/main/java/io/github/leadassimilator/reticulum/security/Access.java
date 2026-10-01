package io.github.leadassimilator.reticulum.security;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The checks every in-game action starts with, so they read and answer the same everywhere: is the block within
 * reach, is there a network, and may the player do this to it. Refusals tell the player why, naming the network only
 * to those who may see it.
 */
public final class Access {
    private Access() {
    }

    /** Whether the block is within the configured interaction distance and loaded. */
    public static boolean inReach(ServerPlayer player, BlockPos pos) {
        double max = ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt();
        return player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= max * max && player.serverLevel().isLoaded(pos);
    }

    /** The network block at {@code pos}, if the player can reach it. */
    @Nullable
    public static NetworkBlockEntity reach(ServerPlayer player, BlockPos pos) {
        return inReach(player, pos) ? Topology.blockEntity(player.serverLevel(), pos) : null;
    }

    /**
     * The network, if the player has at least {@code role} on it; otherwise null, after telling them why (no network,
     * no access, or not enough of it).
     */
    @Nullable
    public static Network require(ServerPlayer player, @Nullable Network network, Role role) {
        if (network == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_network"), true);
            return null;
        }
        if (Security.has(network, player, role)) {
            return network;
        }
        player.displayClientMessage(refusal(network, player, role), true);
        return null;
    }

    /** Why the player can't do something needing {@code role} on the network. */
    public static Component refusal(Network network, ServerPlayer player, Role role) {
        if (!Security.canView(network, player)) {
            return Component.translatable("message.reticulum.no_access_private");
        }
        return switch (role) {
            case VIEWER, USER -> Component.translatable("message.reticulum.no_access", network.name());
            case EDITOR -> Component.translatable("message.reticulum.no_edit_access");
            case ADMIN, OWNER -> Component.translatable("message.reticulum.no_admin_access");
        };
    }
}
