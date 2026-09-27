package io.github.leadassimilator.reticulum.security;

import java.util.Optional;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.world.entity.player.Player;

/** Every server-side permission decision goes through here. */
public final class Security {
    private Security() {
    }

    /** The player's effective role, or empty if they have no access at all. */
    public static Optional<Role> role(Network network, Player player) {
        if (player.getUUID().equals(network.owner())) {
            return Optional.of(Role.OWNER);
        }
        Member member = network.members().get(player.getUUID());
        if (member != null) {
            return Optional.of(member.role());
        }
        if (network.owner() == null || network.accessMode() == AccessMode.PUBLIC) {
            return Optional.of(Role.USER);
        }
        return Optional.empty();
    }

    public static boolean has(Network network, Player player, Role needed) {
        if (role(network, player).map(r -> r.atLeast(needed)).orElse(false)) {
            return true;
        }
        if (ReticulumConfig.OPERATOR_BYPASS.getAsBoolean() && player.hasPermissions(2)) {
            Reticulum.LOGGER.info("Operator {} bypassed {} access on network {} ({})",
                    player.getGameProfile().getName(), needed.getSerializedName(), network.name(), network.id());
            return true;
        }
        return false;
    }

    public static boolean canView(Network network, Player player) {
        return has(network, player, Role.VIEWER);
    }

    /** Joining a connector or cable to the network, or binding one to it. */
    public static boolean canUse(Network network, Player player) {
        return has(network, player, Role.USER);
    }

    public static boolean canEdit(Network network, Player player) {
        return has(network, player, Role.EDITOR);
    }

    public static boolean canAdmin(Network network, Player player) {
        return has(network, player, Role.ADMIN);
    }

    /** Breaking network blocks. Only Private networks restrict it, and only when the config says so. */
    public static boolean canBreak(Network network, Player player) {
        if (network.accessMode() != AccessMode.PRIVATE || !ReticulumConfig.PROTECT_PRIVATE_BLOCKS.getAsBoolean()) {
            return true;
        }
        return has(network, player, Role.USER);
    }
}
