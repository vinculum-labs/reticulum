package io.github.leadassimilator.reticulum.protocol;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Passwords;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side of the in-game quick screens: builds views and applies the edits they send. */
public final class Screens {
    public static final int MAX_NAME_LENGTH = 64;

    private Screens() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        NetworkBlockEntity be = Topology.blockEntity(level, pos);
        if (be == null) {
            return;
        }
        Network network = NetworkManager.network(level.getServer(), Topology.networkOf(level, be));
        if (network != null && !Security.canView(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_access", network.name()), true);
            return;
        }
        if (be instanceof ControllerBlockEntity) {
            if (network == null) {
                // Its network was deleted: give it one again (an orphaned neighbor's, or a new one).
                network = NetworkManager.network(level.getServer(), Topology.reclaimController(level, pos, player));
            }
            if (network != null && Security.canView(network, player)) {
                PacketDistributor.sendToPlayer(player, new Payloads.OpenControllerScreen(controllerView(player, pos, network)));
            }
        } else {
            PacketDistributor.sendToPlayer(player, new Payloads.OpenBlockScreen(blockView(player, level, pos, be, network)));
        }
    }

    private static Views.ControllerView controllerView(ServerPlayer player, BlockPos pos, Network network) {
        return new Views.ControllerView(pos, network.id(), network.name(), network.ownerName(), network.accessMode(), network.passwordHash() != null,
                network.connectors().size(), network.devices().size(), NetworkManager.orphanCount(network),
                Security.canEdit(network, player), Security.canAdmin(network, player),
                player.serverLevel().getBlockEntity(pos) instanceof ControllerBlockEntity controller ? controller.antennaTier() : 0);
    }

    private static Views.BlockView blockView(ServerPlayer player, ServerLevel level, BlockPos pos, NetworkBlockEntity be, @Nullable Network network) {
        boolean connector = be instanceof ConnectorBlockEntity;
        List<Views.SideView> sides = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            sides.add(sideView(level, pos, be, dir, network));
        }
        String defaultName = connector ? "Connector (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")" : "";
        String customName = "";
        if (be instanceof ConnectorBlockEntity c && network != null) {
            ConnectorRecord record = network.connector(c.connectorId());
            if (record != null && record.name() != null) {
                customName = record.name();
            }
        }
        boolean canEdit = network == null || Security.canEdit(network, player);
        return new Views.BlockView(pos, connector, network == null ? "" : network.name(), defaultName, customName, sides, canEdit,
                connector && !Topology.isWired(level, pos));
    }

    private static Views.SideView sideView(ServerLevel level, BlockPos pos, NetworkBlockEntity be, Direction dir, @Nullable Network network) {
        BlockPos next = pos.relative(dir);
        Topology.JointState joint = Topology.jointState(level, pos, dir);
        if (joint != null) {
            Views.JointView view = switch (joint) {
                case CONNECTED -> Views.JointView.CONNECTED;
                case DISCONNECTED -> Views.JointView.DISCONNECTED;
                case CONFLICT -> Views.JointView.CONFLICT;
            };
            return new Views.SideView(dir, Views.SideKind.JOINT, view, true, Devices.icon(level, next),
                    level.getBlockState(next).getBlock().getName().getString(), "", List.of());
        }
        if (be instanceof ConnectorBlockEntity connector && Devices.isDevice(level, next)) {
            DeviceRecord record = network == null ? null : network.device(Devices.global(level, next));
            List<String> faces = new ArrayList<>();
            for (FaceKey face : FaceKey.values()) {
                String name = record == null ? null : record.faceName(face);
                faces.add(name == null ? "" : name);
            }
            return new Views.SideView(dir, Views.SideKind.DEVICE, Views.JointView.DISCONNECTED, connector.isDeviceEnabled(dir),
                    Devices.icon(level, next), Devices.defaultName(level, next),
                    record == null || record.name() == null ? "" : record.name(), faces);
        }
        return new Views.SideView(dir, Views.SideKind.NONE, Views.JointView.DISCONNECTED, false, ItemStack.EMPTY, "", "", List.of());
    }

    /** Answers the built-in HUD. Limited to blocks the player could plausibly be looking at. */
    static void lookAt(ServerPlayer player, Payloads.LookRequest payload) {
        if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 32 * 32 || !player.serverLevel().isLoaded(payload.pos())) {
            return;
        }
        var lines = io.github.leadassimilator.reticulum.lookat.LookAt.describe(player.serverLevel(), payload.pos(), payload.face(), player);
        PacketDistributor.sendToPlayer(player, new Payloads.LookResponse(payload.pos(), lines.size() > 16 ? lines.subList(0, 16) : lines));
    }

    // ---------------------------------------------------------------- edits

    /** Checks the player can reach the block; every edit starts here. */
    @Nullable
    private static NetworkBlockEntity reachable(ServerPlayer player, BlockPos pos) {
        int max = ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt();
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > (double) max * max) {
            return null;
        }
        return Topology.blockEntity(player.serverLevel(), pos);
    }

    static void toggleSide(ServerPlayer player, Payloads.ToggleSide payload) {
        if (reachable(player, payload.pos()) == null) {
            return;
        }
        Component message = Topology.toggle(player.serverLevel(), payload.pos(), payload.side(), player);
        player.displayClientMessage(message, true);
        open(player, payload.pos());
    }

    static void renameConnector(ServerPlayer player, Payloads.RenameConnector payload) {
        if (!(reachable(player, payload.pos()) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = editableNetwork(player, connector);
        if (network == null) {
            return;
        }
        ConnectorRecord record = network.connector(connector.connectorId());
        if (record != null) {
            record.setName(clip(payload.name()));
            NetworkManager.changed(player.server, network);
        }
        open(player, payload.pos());
    }

    static void renameDevice(ServerPlayer player, Payloads.RenameDevice payload) {
        if (!(reachable(player, payload.pos()) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = editableNetwork(player, connector);
        if (network == null) {
            return;
        }
        GlobalPos device = Devices.global(player.level(), payload.pos().relative(payload.side()));
        DeviceRecord record = network.device(device);
        if (record == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.not_a_device"), true);
            return;
        }
        if (payload.face().isPresent()) {
            record.setFaceName(payload.face().get(), clip(payload.name()));
        } else {
            record.setName(clip(payload.name()));
        }
        NetworkManager.changed(player.server, network);
        open(player, payload.pos());
    }

    static void controllerSettings(ServerPlayer player, Payloads.ControllerSettings payload) {
        if (!(reachable(player, payload.pos()) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = NetworkManager.network(player.server, controller.networkId());
        if (network == null) {
            return;
        }
        if (Security.canEdit(network, player)) {
            network.setName(clip(payload.name()));
        }
        if (Security.canAdmin(network, player)) {
            if (!payload.password().isEmpty()) {
                network.setPasswordHash(Passwords.hash(payload.password()));
            }
            if (payload.mode() == AccessMode.PASSWORD && network.passwordHash() == null) {
                player.displayClientMessage(Component.translatable("message.reticulum.password_required"), true);
            } else {
                network.setAccessMode(payload.mode());
            }
        }
        NetworkManager.changed(player.server, network);
        open(player, payload.pos());
    }

    @Nullable
    private static Network editableNetwork(ServerPlayer player, NetworkBlockEntity be) {
        Network network = NetworkManager.network(player.server, Topology.networkOf(player.serverLevel(), be));
        if (network == null) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_network"), true);
            return null;
        }
        if (!Security.canEdit(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_edit_access"), true);
            return null;
        }
        return network;
    }

    private static String clip(String name) {
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }
}
