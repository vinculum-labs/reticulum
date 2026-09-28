package io.github.leadassimilator.reticulum.item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
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
 * </ul>
 */
public class NetworkToolItem extends Item {
    public NetworkToolItem(Properties properties) {
        super(properties);
    }

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

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof NetworkBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            if (mode(context.getItemInHand()) == ToolMode.LINK) {
                link(level, player, context.getClickedPos(), context.getItemInHand());
            } else {
                Direction side = targetSide(player, level.getBlockState(context.getClickedPos()), context.getClickedPos(),
                        context.getClickedFace(), 1.0f);
                player.displayClientMessage(Topology.toggle(level, context.getClickedPos(), side, player), true);
            }
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    private static void link(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack tool) {
        NetworkBlockEntity be = Topology.blockEntity(level, pos);
        if (be == null) {
            return;
        }
        if (player.isShiftKeyDown()) {
            Network network = Topology.network(level, pos);
            if (network == null || !Security.canView(network, player)) {
                player.displayClientMessage(Component.translatable(network == null ? "message.reticulum.no_network" : "message.reticulum.no_access",
                        network == null ? "" : network.name()), true);
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
        if (mode(stack) != ToolMode.LINK) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                stack.remove(ModDataComponents.NETWORK_ID.get());
                stack.remove(ModDataComponents.NETWORK_NAME.get());
                serverPlayer.displayClientMessage(Component.translatable("message.reticulum.tool_cleared"), true);
            } else {
                Linking.openPicker(serverPlayer, Optional.empty());
            }
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
