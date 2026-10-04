package io.github.leadassimilator.reticulum.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import io.github.leadassimilator.reticulum.security.NetworkAdmin;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Passwords;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /reticulum}: listing networks, joining password networks, and managing members until the web UI exists.
 * Networks are named by their name (quoted if it has spaces) or the start of their id.
 */
public final class ReticulumCommand {
    private static final SimpleCommandExceptionType UNKNOWN_NETWORK = new SimpleCommandExceptionType(Component.translatable("command.reticulum.unknown_network"));
    private static final SimpleCommandExceptionType NO_ACCESS = new SimpleCommandExceptionType(Component.translatable("command.reticulum.no_access"));

    private static final SuggestionProvider<CommandSourceStack> NETWORKS = (ctx, builder) -> SharedSuggestionProvider.suggest(
            NetworkManager.data(ctx.getSource().getServer()).networks().stream()
                    .filter(n -> ctx.getSource().getPlayer() == null || Security.canView(n, ctx.getSource().getPlayer())
                            || n.accessMode() == AccessMode.PASSWORD)
                    .map(n -> StringArgumentType.escapeIfRequired(n.name())), builder);
    private static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, builder) -> SharedSuggestionProvider.suggest(
            List.of("viewer", "user", "editor", "admin"), builder);

    private ReticulumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reticulum")
                .then(Commands.literal("list").executes(ReticulumCommand::list))
                .then(Commands.literal("join")
                        .then(Commands.argument("network", StringArgumentType.string()).suggests(NETWORKS)
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ReticulumCommand::join))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("network", StringArgumentType.string()).suggests(NETWORKS)
                                .executes(ReticulumCommand::delete)))
                .then(Commands.literal("merge")
                        .then(Commands.argument("from", StringArgumentType.string()).suggests(NETWORKS)
                                .then(Commands.argument("into", StringArgumentType.string()).suggests(NETWORKS)
                                        .executes(ReticulumCommand::merge))))
                .then(Commands.literal("member")
                        .then(Commands.argument("network", StringArgumentType.string()).suggests(NETWORKS)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                                                        .executes(ReticulumCommand::addMember))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .executes(ReticulumCommand::removeMember))))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        List<Network> visible = new ArrayList<>();
        for (Network network : NetworkManager.data(ctx.getSource().getServer()).networks()) {
            if (player == null || Security.canView(network, player) || network.accessMode() == AccessMode.PASSWORD) {
                visible.add(network);
            }
        }
        if (visible.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.list_empty"), false);
            return 0;
        }
        for (Network n : visible) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.list_entry", n.name(), n.id().toString().substring(0, 8),
                    n.ownerName().isEmpty() ? "-" : n.ownerName(), Component.translatable("screen.reticulum.access." + n.accessMode().getSerializedName()),
                    n.connectors().size(), n.devices().size()), false);
        }
        return visible.size();
    }

    private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Network network = network(ctx);
        if (Security.canUse(network, player)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.already_member", network.name()), false);
            return 1;
        }
        try {
            NetworkAdmin.join(network, player.getUUID(), player.getGameProfile().getName(), StringArgumentType.getString(ctx, "password"));
        } catch (NetworkAdmin.Refused e) {
            throw new SimpleCommandExceptionType(e.message()).create();
        }
        NetworkManager.changed(ctx.getSource().getServer(), network);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.joined", network.name()), false);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network network = network(ctx);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && !Security.has(network, player, Role.OWNER)) {
            throw NO_ACCESS.create();
        }
        NetworkManager.delete(ctx.getSource().getServer(), network);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.deleted", network.name()), true);
        return 1;
    }

    private static int merge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network from = find(ctx.getSource(), StringArgumentType.getString(ctx, "from"));
        Network into = find(ctx.getSource(), StringArgumentType.getString(ctx, "into"));
        if (from == null || into == null) {
            throw UNKNOWN_NETWORK.create();
        }
        if (from == into) {
            throw new SimpleCommandExceptionType(Component.translatable("command.reticulum.merge_same")).create();
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        // The merged network is gone and its contents change hands: its owner's call, as deleting it is.
        if (player != null && (!Security.canAdmin(from, player) || !Security.canAdmin(into, player) || !Security.mayAbsorb(from, into, player))) {
            throw NO_ACCESS.create();
        }
        // Any controller of its own (running or not: switched off, its chunk unloaded): its block would resolve to the
        // merged network next to that one's own controller.
        if (from.controller() != null) {
            throw new SimpleCommandExceptionType(Component.translatable("command.reticulum.merge_has_controller", from.name())).create();
        }
        Role cap = player == null ? Role.ADMIN : Security.role(into, player).orElse(Role.ADMIN);
        NetworkManager.absorb(ctx.getSource().getServer(), into, from, cap);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.merged", from.name(), into.name()), true);
        return 1;
    }

    private static int addMember(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network network = adminNetwork(ctx);
        Role role;
        try {
            role = Role.CODEC.parse(com.mojang.serialization.JavaOps.INSTANCE, StringArgumentType.getString(ctx, "role").toLowerCase(Locale.ROOT))
                    .getOrThrow(IllegalArgumentException::new);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(Component.translatable("command.reticulum.unknown_role")).create();
        }
        int count = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
            try {
                NetworkAdmin.setMember(network, profile, role);
            } catch (NetworkAdmin.Refused e) {
                throw new SimpleCommandExceptionType(e.message()).create();
            }
            count++;
        }
        NetworkManager.changed(ctx.getSource().getServer(), network);
        int added = count;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.member_added", added, network.name()), true);
        return count;
    }

    private static int removeMember(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network network = adminNetwork(ctx);
        int count = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
            if (network.members().remove(profile.getId()) != null) {
                count++;
            }
        }
        NetworkManager.changed(ctx.getSource().getServer(), network);
        int removed = count;
        ctx.getSource().sendSuccess(() -> Component.translatable("command.reticulum.member_removed", removed, network.name()), true);
        return count;
    }

    private static Network adminNetwork(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network network = network(ctx);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && !Security.canAdmin(network, player)) {
            throw NO_ACCESS.create();
        }
        return network;
    }

    private static Network network(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Network network = find(ctx.getSource(), StringArgumentType.getString(ctx, "network"));
        if (network == null) {
            throw UNKNOWN_NETWORK.create();
        }
        return network;
    }

    @Nullable
    private static Network find(CommandSourceStack source, String query) {
        Network byPrefix = null;
        for (Network network : NetworkManager.data(source.getServer()).networks()) {
            if (network.name().equalsIgnoreCase(query)) {
                return network;
            }
            if (query.length() >= 4 && network.id().toString().startsWith(query.toLowerCase(Locale.ROOT))) {
                byPrefix = network;
            }
        }
        return byPrefix;
    }
}
