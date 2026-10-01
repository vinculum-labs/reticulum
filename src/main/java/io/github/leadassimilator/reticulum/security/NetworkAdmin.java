package io.github.leadassimilator.reticulum.security;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.github.leadassimilator.reticulum.network.Limits;
import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.network.chat.Component;

/**
 * The rules for a network's access and members, the same from the web editor, the controller screen, the network
 * picker and /reticulum. Each caller checks the player may make the change (admin, mostly) and turns a refusal into
 * its own kind of message; the caller also marks the network changed.
 */
public final class NetworkAdmin {
    private NetworkAdmin() {
    }

    /**
     * Why a change was refused. {@link #message()} for players in game (translated on their client); {@link #getMessage()}
     * is English, for the web editor (a server has no translations of its own).
     */
    public static final class Refused extends Exception {
        private final String key;
        private final Object[] args;

        Refused(String key, String english, Object... args) {
            super(english);
            this.key = key;
            this.args = args;
        }

        public Component message() {
            return Component.translatable(key, args);
        }
    }

    /** Joins a password network as a user, if the password is right (one guess a second per player). */
    public static void join(Network network, UUID player, String name, String password) throws Refused {
        String hash = network.passwordHash();
        if (network.accessMode() != AccessMode.PASSWORD || hash == null || !Passwords.guess(player, password, hash)) {
            throw new Refused("message.reticulum.wrong_password", "Wrong password for " + network.name(), network.name());
        }
        addMember(network, new Member(player, name, Role.USER));
    }

    /** Gives a player a role on the network (a new member, or a changed role). Ownership can't be given this way. */
    public static void setMember(Network network, GameProfile profile, Role role) throws Refused {
        if (role == Role.OWNER) {
            throw new Refused("command.reticulum.unknown_role", "Ownership can't be given away here");
        }
        if (profile.getId().equals(network.owner())) {
            throw new Refused("message.reticulum.member_is_owner", profile.getName() + " owns the network", profile.getName());
        }
        addMember(network, new Member(profile.getId(), profile.getName(), role));
    }

    private static void addMember(Network network, Member member) throws Refused {
        if (!network.members().containsKey(member.id()) && network.members().size() >= Limits.MEMBERS) {
            throw new Refused("message.reticulum.too_many_members", "A network can have at most " + Limits.MEMBERS + " members", Limits.MEMBERS);
        }
        network.members().put(member.id(), member);
    }

    /** Password access needs a password first. */
    public static void setAccess(Network network, AccessMode mode) throws Refused {
        if (mode == AccessMode.PASSWORD && network.passwordHash() == null) {
            throw new Refused("message.reticulum.password_required", "Set a password before choosing password access");
        }
        network.setAccessMode(mode);
    }

    public static void setPassword(Network network, String password) throws Refused {
        if (password.isEmpty()) {
            throw new Refused("message.reticulum.password_empty", "The password can't be empty");
        }
        network.setPasswordHash(Passwords.hash(password));
    }
}
