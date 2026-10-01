package io.github.leadassimilator.reticulum.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.item.ToolMode;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Passwords;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Binding wireless connectors: the network picker (for the network tool, or a connector's screen), joining a
 * password network from it, and the tool's mode.
 */
public final class Linking {
    private Linking() {
    }

    /** Networks the player can bind to, and password networks they could join. */
    public static void openPicker(ServerPlayer player, Optional<BlockPos> connector) {
        if (connector.isPresent() && !canReachConnector(player, connector.get())) {
            return;
        }
        List<Payloads.NetworkChoice> choices = new ArrayList<>();
        for (Network network : NetworkManager.data(player.server).networks()) {
            boolean member = Security.canUse(network, player);
            boolean password = !member && network.accessMode() == AccessMode.PASSWORD && network.passwordHash() != null;
            if (member || password) {
                choices.add(new Payloads.NetworkChoice(network.id(), network.name(), network.ownerName(), password));
            }
        }
        choices.sort(Comparator.comparing(Payloads.NetworkChoice::name, String.CASE_INSENSITIVE_ORDER));
        PacketDistributor.sendToPlayer(player, new Payloads.OpenNetworkPicker(connector, choices));
    }

    /** The network tool in hand forgets its network. */
    public static void unlinkTool(ServerPlayer player) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof io.github.leadassimilator.reticulum.item.NetworkToolItem
                    && stack.has(io.github.leadassimilator.reticulum.registry.ModDataComponents.NETWORK_ID.get())) {
                stack.remove(io.github.leadassimilator.reticulum.registry.ModDataComponents.NETWORK_ID.get());
                stack.remove(io.github.leadassimilator.reticulum.registry.ModDataComponents.NETWORK_NAME.get());
                player.displayClientMessage(Component.translatable("message.reticulum.tool_cleared"), true);
                return;
            }
        }
    }

    public static void pick(ServerPlayer player, Payloads.PickNetwork payload) {
        Network network = NetworkManager.network(player.server, payload.network());
        if (network == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.network_gone_bound"), true);
            return;
        }
        if (!Security.canUse(network, player)) {
            String hash = network.passwordHash();
            if (network.accessMode() != AccessMode.PASSWORD || hash == null || !Passwords.guess(player.getUUID(), payload.password(), hash)) {
                player.displayClientMessage(Component.translatable("message.reticulum.wrong_password", network.name()), true);
                return;
            }
            network.members().put(player.getUUID(), new Member(player.getUUID(), player.getGameProfile().getName(), Role.USER));
            NetworkManager.changed(player.server, network);
            player.displayClientMessage(Component.translatable("command.reticulum.joined", network.name()), false);
        }
        if (payload.connector().isPresent()) {
            BlockPos pos = payload.connector().get();
            if (canReachConnector(player, pos)) {
                player.displayClientMessage(Topology.bindWireless(player.serverLevel(), pos, network, player), true);
                Screens.open(player, pos);
            }
            return;
        }
        ItemStack tool = heldTool(player);
        if (!tool.isEmpty()) {
            link(tool, network);
            player.displayClientMessage(Component.translatable("message.reticulum.tool_linked", network.name()), true);
        }
    }

    /** Stores a network in the tool, for pasting onto connectors. */
    public static void link(ItemStack tool, Network network) {
        tool.set(ModDataComponents.NETWORK_ID.get(), network.id());
        tool.set(ModDataComponents.NETWORK_NAME.get(), network.name());
    }

    public static void cycleMode(ServerPlayer player, int step) {
        ItemStack tool = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(tool.getItem() instanceof NetworkToolItem)) {
            return;
        }
        ToolMode mode = NetworkToolItem.mode(tool).next(step);
        tool.set(ModDataComponents.TOOL_MODE.get(), mode);
        player.displayClientMessage(Component.translatable("message.reticulum.tool_mode", Component.translatable(mode.translationKey())), true);
    }

    private static ItemStack heldTool(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NetworkToolItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean canReachConnector(ServerPlayer player, BlockPos pos) {
        double max = ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt();
        return player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= max * max
                && player.serverLevel().getBlockEntity(pos) instanceof ConnectorBlockEntity;
    }

    /** For the connector screen: whether the player may pick a network for it (no cable decides it). */
    public static boolean bindable(ServerPlayer player, BlockPos pos) {
        return !Topology.isWired(player.serverLevel(), pos);
    }
}
