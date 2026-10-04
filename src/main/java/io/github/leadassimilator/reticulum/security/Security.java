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

    private static final long BYPASS_LOG_INTERVAL_MS = 10 * 60_000L;
    private static final java.util.Map<String, Long> BYPASS_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean has(Network network, Player player, Role needed) {
        if (role(network, player).map(r -> r.atLeast(needed)).orElse(false)) {
            return true;
        }
        if (ReticulumConfig.OPERATOR_BYPASS.getAsBoolean() && player.hasPermissions(2)) {
            long now = System.currentTimeMillis();
            String key = player.getUUID() + "/" + network.id() + "/" + needed.getSerializedName();
            Long last = BYPASS_LOGGED.get(key);
            if (last == null || now - last > BYPASS_LOG_INTERVAL_MS) {
                BYPASS_LOGGED.put(key, now);
                Reticulum.LOGGER.info("Operator {} bypassed {} access on network {} ({})",
                        player.getGameProfile().getName(), needed.getSerializedName(), network.name(), network.id());
            }
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

    /**
     * Merging {@code from} into {@code into}: {@code from} is deleted and everything in it changes hands, so it takes
     * what deleting it takes, being its owner. Not when both have the same owner, or {@code from} has none.
     */
    public static boolean mayAbsorb(Network from, Network into, Player player) {
        if (from.owner() == null || from.owner().equals(into.owner())) {
            return true;
        }
        return has(from, player, Role.OWNER);
    }

    /** Breaking network blocks. Only Private networks restrict it, and only when the config says so. */
    public static boolean canBreak(Network network, Player player) {
        if (network.accessMode() != AccessMode.PRIVATE || !ReticulumConfig.PROTECT_PRIVATE_BLOCKS.getAsBoolean()) {
            return true;
        }
        return has(network, player, Role.USER);
    }
}
