package io.github.leadassimilator.reticulum.item;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.entity.CableBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.client.AlarmClient;
import io.github.leadassimilator.reticulum.client.web.WebBridge;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
import io.github.leadassimilator.reticulum.network.Limits;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.protocol.Linking;
import io.github.leadassimilator.reticulum.protocol.Screens;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.security.Access;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
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
    public static final Identifier MODE_PROPERTY =
            Identifier.fromNamespaceAndPath(Reticulum.MODID, "mode");
    /** The item property for an alarm going off: 0 nothing, 0.5 a warning, 1 critical (set on the client). */
    public static final Identifier ALARM_PROPERTY =
            Identifier.fromNamespaceAndPath(Reticulum.MODID, "alarm");

    public static ToolMode mode(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.TOOL_MODE.get(), ToolMode.CONFIGURE);
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().append(" (").append(Component.translatable(mode(stack).translationKey())).append(")");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        if (stack.has(ModDataComponents.NETWORK_ID.get())) {
            tooltip.accept(Component.translatable("tooltip.reticulum.linked_to", stack.getOrDefault(ModDataComponents.NETWORK_NAME.get(), "?"))
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(Component.translatable("tooltip.reticulum.tool_" + mode(stack).getSerializedName()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(Component.translatable("tooltip.reticulum.tool_switch").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Identify mode acts before the block does, so clicking a chest or machine lights it up instead of opening it. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        // Status mode changes nothing in the world: a right-click anywhere is its settings.
        if (mode(stack) == ToolMode.STATUS) {
            if (context.getLevel().isClientSide() && context.getPlayer() != null && !context.getPlayer().isShiftKeyDown()) {
                AlarmClient.openSettings();
            }
            return InteractionResult.SUCCESS;
        }
        if (mode(stack) != ToolMode.IDENTIFY) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            identify(level, player, context.getClickedPos(), context.getClickedFace());
        }
        return InteractionResult.SUCCESS;
    }

    /** The network tool the player holds, in either hand (the main hand first), or empty. */
    public static ItemStack held(Player player) {
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
            } else if (player.isShiftKeyDown() && level.getBlockEntity(context.getClickedPos()) instanceof ControllerBlockEntity) {
                ControllerBlock.removeAntenna(player, level, context.getClickedPos());
            } else {
                Direction side = targetSide(player, level.getBlockState(context.getClickedPos()), context.getClickedPos(),
                        context.getClickedFace(), 1.0f);
                player.displayClientMessage(Topology.toggle(level, context.getClickedPos(), side, player), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Identify mode: a connector lights up with every device it reaches (its plug aimed at: that device); any other block
     * lights up as a device (sneak: just the clicked face). A cable is never a device: clicking one does nothing.
     */
    private static void identify(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        if (level.getBlockEntity(pos) instanceof CableBlockEntity) {
            return;
        }
        if (Topology.blockEntity(level, pos) instanceof ConnectorBlockEntity connector) {
            // A plug aimed at: the device it reaches (sneaking, just the face touching it). Anywhere else on the
            // connector: the connector and every device it reaches.
            Direction plug = plugAimedAt(level, player, pos);
            if (plug != null) {
                if (player.isShiftKeyDown()) {
                    toggleFace(level, player, pos.relative(plug), plug.getOpposite(), false);
                } else {
                    toggleDevice(level, player, pos.relative(plug));
                }
                return;
            }
            identifyConnector(level, player, pos, connector);
            return;
        }
        if (player.isShiftKeyDown()) {
            identifyFace(level, player, pos, face);
            return;
        }
        toggleDevice(level, player, pos);
    }

    /** The side of a connector whose plug the player aims at, when a device of a network they may use is there. */
    @Nullable
    private static Direction plugAimedAt(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Vec3 from = player.getEyePosition(1.0f);
        Vec3 to = from.add(player.getViewVector(1.0f).scale(player.blockInteractionRange() + 1));
        if (!(level.getBlockState(pos).getBlock() instanceof ConnectorBlock block)) {
            return null;
        }
        Set<Direction> plugs = EnumSet.noneOf(Direction.class);
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos).getValue(block.linkProperties().get(dir)) == Link.DEVICE) {
                plugs.add(dir);
            }
        }
        Direction side = Topology.armHit(pos, true, from, to, plugs);
        if (side == null || !plugs.contains(side)) {
            return null;
        }
        return deviceNetwork(level, player, Devices.global(level, pos.relative(side))) != null ? side : null;
    }

    /** A connector and every device it reaches, lit up together (or all stopped, when the connector was lit). */
    private static void identifyConnector(ServerLevel level, ServerPlayer player, BlockPos pos, ConnectorBlockEntity connector) {
        Network network = Access.require(player, Topology.network(level, pos), Role.USER);
        if (network == null) {
            return;
        }
        Identify self = Identify.connector(connector.connectorId(), 0);
        boolean on = !Identifies.isOn(network, self);
        var record = network.connector(connector.connectorId());
        List<Identify> targets = new ArrayList<>(List.of(self));
        for (GlobalPos device : record == null ? List.<GlobalPos>of() : record.devices()) {
            targets.add(Identifies.deviceOrFace(device, null));
        }
        List<Identify> changed = Identifies.setAll(level.getServer(), network, targets, on);
        if (on && !changed.contains(self)) {
            player.displayClientMessage(Component.translatable("message.reticulum.identify_full", Limits.IDENTIFIES), true);
            return;
        }
        int devices = changed.size() - (changed.contains(self) ? 1 : 0);
        long refused = on ? targets.stream().filter(t -> !Identifies.isOn(network, t)).count() : 0;
        if (refused > 0) {
            player.displayClientMessage(Component.translatable("message.reticulum.identify_some", devices, refused,
                    Limits.IDENTIFIES), true);
            return;
        }
        Component what = devices == 0 ? Component.translatable("screen.reticulum.connector")
                : devices == 1 ? Component.translatable("message.reticulum.connector_and_device")
                : Component.translatable("message.reticulum.connector_and_devices", devices);
        player.displayClientMessage(Component.translatable(on ? "message.reticulum.identified" : "message.reticulum.unidentified", what), true);
    }

    /** A device lit up whole (or stopped). */
    private static void toggleDevice(ServerLevel level, ServerPlayer player, BlockPos pos) {
        GlobalPos device = Devices.global(level, pos);
        Network found = deviceNetwork(level, player, device);
        if (found == null) {
            notADevice(level, player, pos);
            return;
        }
        Identify target = Identifies.deviceOrFace(device, null);
        boolean was = Identifies.isOn(found, target);
        boolean on = Identifies.toggle(level.getServer(), found, target);
        if (!was && !on) {
            player.displayClientMessage(Component.translatable("message.reticulum.identify_full", Limits.IDENTIFIES), true);
            return;
        }
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
            notADevice(level, player, pos);
            return;
        }
        Identify target = Identifies.deviceOrFace(device, face);
        boolean was = Identifies.isOn(network, target);
        boolean on = offOnly ? Identifies.set(level.getServer(), network, target, false) : Identifies.toggle(level.getServer(), network, target);
        if (!offOnly && !was && !on) {
            player.displayClientMessage(Component.translatable("message.reticulum.identify_full", Limits.IDENTIFIES), true);
            return;
        }
        String name = Identifies.label(level.getServer(), network, target);
        player.displayClientMessage(Component.translatable(on ? "message.reticulum.identified" : "message.reticulum.unidentified", name), true);
    }

    /** Says the block isn't a device of a network the player may use; a block of the mod itself needs no saying. */
    private static void notADevice(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (Topology.blockEntity(level, pos) == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_device_of_yours"), true);
        }
    }

    /** The first network the player may use that has this device. */
    @Nullable
    private static Network deviceNetwork(ServerLevel level, ServerPlayer player, GlobalPos device) {
        return NetworkManager.networksWith(level.getServer(), device).stream().filter(network -> Security.canUse(network, player)).findFirst()
                .orElse(null);
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
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Sneak + right-click the air, in any mode: the editor, on the network the tool is linked to (or the last one
        // edited), so there's no need to go back to a controller.
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                WebBridge.openEditor(stack.get(ModDataComponents.NETWORK_ID.get()));
            }
            return InteractionResult.SUCCESS;
        }
        if (mode(stack) == ToolMode.STATUS) {
            if (level.isClientSide()) {
                AlarmClient.openSettings();
            }
            return InteractionResult.SUCCESS;
        }
        if (mode(stack) != ToolMode.LINK) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Linking.openPicker(serverPlayer, Optional.empty());
        }
        return InteractionResult.SUCCESS;
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
