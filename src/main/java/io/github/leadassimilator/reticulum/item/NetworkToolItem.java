package io.github.leadassimilator.reticulum.item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Access;
import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.protocol.Linking;
import io.github.leadassimilator.reticulum.protocol.Screens;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The network tool, with modes switched by sneak + scroll.
 * <ul>
 * <li>Configure: right-click a joint of a cable or connector to connect or disconnect it, or a connector's device
 * side to attach or detach that device.</li>
 * <li>Link (like an AE2 memory card): sneak + right-click a controller or connector to copy its network; right-click
 * a connector to bind it to that network; right-click the air to pick a network, sneak + right-click the air to
 * clear. Only connectors no cable joins can be bound: a cable decides the network of what it joins.</li>
 * <li>Identify: right-click a connector or device to light it up (or off again); sneak + right-click lights just the
 * clicked face of a device.</li>
 * </ul>
 */
public class NetworkToolItem extends Item {
    public NetworkToolItem(Properties properties) {
        super(properties);
    }

    /** The item property its look follows: 0 Configure, 0.5 Link, 1 Identify (the item model picks a head color). */
    public static final net.minecraft.resources.ResourceLocation MODE_PROPERTY =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(io.github.leadassimilator.reticulum.Reticulum.MODID, "mode");

    public static ToolMode mode(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.TOOL_MODE.get(), ToolMode.CONFIGURE);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(getDescriptionId(stack)).append(" (").append(Component.translatable(mode(stack).translationKey())).append(")");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        if (stack.has(ModDataComponents.NETWORK_ID.get())) {
            tooltip.add(Component.translatable("tooltip.reticulum.linked_to", stack.getOrDefault(ModDataComponents.NETWORK_NAME.get(), "?"))
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.reticulum.tool_" + mode(stack).getSerializedName()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.reticulum.tool_switch").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Identify mode acts before the block does, so clicking a chest or machine lights it up instead of opening it. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (mode(stack) != ToolMode.IDENTIFY) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            identify(level, player, context.getClickedPos(), context.getClickedFace());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /** The network tool the player holds, in either hand (the main hand first), or empty. */
    public static ItemStack held(net.minecraft.world.entity.player.Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NetworkToolItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof NetworkBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            if (mode(context.getItemInHand()) == ToolMode.LINK) {
                link(level, player, context.getClickedPos(), context.getItemInHand());
            } else if (player.isShiftKeyDown() && LinkedBlock.removeFacade(level, context.getClickedPos(), player)) {
                // Taken off, or refused; either way the click is done.
            } else if (player.isShiftKeyDown() && level.getBlockEntity(context.getClickedPos()) instanceof io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity) {
                io.github.leadassimilator.reticulum.block.ControllerBlock.removeAntenna(player, level, context.getClickedPos());
            } else {
                Direction side = targetSide(player, level.getBlockState(context.getClickedPos()), context.getClickedPos(),
                        context.getClickedFace(), 1.0f);
                player.displayClientMessage(Topology.toggle(level, context.getClickedPos(), side, player), true);
            }
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /** Identify mode: a connector lights up itself; any other block lights up as a device (sneak: just the clicked face). */
    private static void identify(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        if (player.isShiftKeyDown()) {
            identifyFace(level, player, pos, face);
            return;
        }
        if (Topology.blockEntity(level, pos) instanceof ConnectorBlockEntity connector) {
            Network network = Access.require(player, Topology.network(level, pos), Role.USER);
            if (network == null) {
                return;
            }
            boolean on = Identifies.toggle(level.getServer(), network, Identify.connector(connector.connectorId(), 0));
            player.displayClientMessage(Component.translatable(on ? "message.reticulum.identified" : "message.reticulum.unidentified",
                    Component.translatable("screen.reticulum.connector")), true);
            return;
        }
        GlobalPos device = Devices.global(level, pos);
        Network found = null;
        for (Network network : NetworkManager.data(level.getServer()).networks()) {
            if (network.device(device) != null && Security.canUse(network, player)) {
                found = network;
                break;
            }
        }
        if (found == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_device_of_yours"), true);
            return;
        }
        Identify target = Identifies.deviceOrFace(device, null);
        boolean on = Identifies.toggle(level.getServer(), found, target);
        String name = Identifies.deviceName(level.getServer(), found, device);
        player.displayClientMessage(Component.translatable(on ? "message.reticulum.identified" : "message.reticulum.unidentified", name), true);
    }

    /**
     * Sneak + right-click: one face of a device. A face lit up is drawn on the surface it shares with whatever touches it,
     * and aiming there often hits the neighbor instead (a connector's plug, another device), so both sides of the
     * surface are considered: a connector side facing a device means that device's face, and a lit face on either
     * side of the surface is the one turned off.
     */
    private static void identifyFace(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        if (Topology.blockEntity(level, pos) instanceof ConnectorBlockEntity) {
            // The side aimed at (a plug reaching a device): that device's face touching it.
            Direction side = targetSide(player, level.getBlockState(pos), pos, face, 1.0f);
            BlockPos device = pos.relative(side);
            if (deviceNetwork(level, player, Devices.global(level, device)) != null) {
                toggleFace(level, player, device, side.getOpposite(), false);
            } else {
                player.displayClientMessage(Component.translatable("message.reticulum.not_a_device_of_yours"), true);
            }
            return;
        }
        // The face on the other side of the surface, if it is the one lit.
        BlockPos other = pos.relative(face);
        Network otherNetwork = deviceNetwork(level, player, Devices.global(level, other));
        if (!faceOn(level, player, pos, face) && otherNetwork != null
                && Identifies.isOn(otherNetwork, Identifies.deviceOrFace(Devices.global(level, other), face.getOpposite()))) {
            toggleFace(level, player, other, face.getOpposite(), true);
            return;
        }
        toggleFace(level, player, pos, face, false);
    }

    private static boolean faceOn(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        Network network = deviceNetwork(level, player, Devices.global(level, pos));
        return network != null && Identifies.isOn(network, Identifies.deviceOrFace(Devices.global(level, pos), face));
    }

    /** Toggles one face of a device (or only turns it off, when {@code offOnly}), with a message saying which. */
    private static void toggleFace(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face, boolean offOnly) {
        GlobalPos device = Devices.global(level, pos);
        Network network = deviceNetwork(level, player, device);
        if (network == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_device_of_yours"), true);
            return;
        }
        Identify target = Identifies.deviceOrFace(device, face);
        boolean on = offOnly ? Identifies.set(level.getServer(), network, target, false) : Identifies.toggle(level.getServer(), network, target);
        String name = Identifies.label(level.getServer(), network, target);
        player.displayClientMessage(Component.translatable(on ? "message.reticulum.identified" : "message.reticulum.unidentified", name), true);
    }

    /** The first network the player may use that has this device. */
    @javax.annotation.Nullable
    private static Network deviceNetwork(ServerLevel level, ServerPlayer player, GlobalPos device) {
        for (Network network : NetworkManager.data(level.getServer()).networks()) {
            if (network.device(device) != null && Security.canUse(network, player)) {
                return network;
            }
        }
        return null;
    }

    private static void link(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack tool) {
        NetworkBlockEntity be = Topology.blockEntity(level, pos);
        if (be == null) {
            return;
        }
        if (player.isShiftKeyDown()) {
            Network network = Access.require(player, Topology.network(level, pos), Role.VIEWER);
            if (network == null) {
                return;
            }
            Linking.link(tool, network);
            player.displayClientMessage(Component.translatable("message.reticulum.tool_linked", network.name()), true);
            return;
        }
        if (be instanceof ControllerBlockEntity) {
            // Right-clicking a controller with the tool still opens it.
            Screens.open(player, pos);
            return;
        }
        if (!(be instanceof ConnectorBlockEntity)) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_connector"), true);
            return;
        }
        UUID id = tool.get(ModDataComponents.NETWORK_ID.get());
        Network network = NetworkManager.network(level.getServer(), id);
        if (network == null) {
            player.displayClientMessage(Component.translatable(id == null ? "message.reticulum.tool_empty" : "message.reticulum.network_gone_bound"), true);
            return;
        }
        player.displayClientMessage(Topology.bindWireless(level, pos, network, player), true);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Sneak + right-click the air, in any mode: the editor, on the network the tool is linked to (or the last one
        // edited), so there's no need to go back to a controller.
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                io.github.leadassimilator.reticulum.client.web.WebBridge.openEditor(stack.get(ModDataComponents.NETWORK_ID.get()));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (mode(stack) != ToolMode.LINK) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Linking.openPicker(serverPlayer, Optional.empty());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** The side of a network block the player is aiming at. Used for both the click and the client outline. */
    public static Direction targetSide(Player player, BlockState state, BlockPos pos, Direction face, float partialTick) {
        if (!(state.getBlock() instanceof LinkedBlock)) {
            return face;
        }
        Vec3 from = player.getEyePosition(partialTick);
        Vec3 to = from.add(player.getViewVector(partialTick).scale(player.blockInteractionRange() + 1));
        return Topology.targetSide(pos, state.getBlock() instanceof ConnectorBlock, from, to, face);
    }
}
