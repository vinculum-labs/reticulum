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
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
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

    /** The ID buttons in the connector screens: the connector itself, the device on a side, or one face of it. */
    public static void toggleIdentify(ServerPlayer player, Payloads.ToggleIdentify payload) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = payload.pos();
        double max = ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt();
        if (player.position().distanceToSqr(Vec3.atCenterOf(pos)) > max * max
                || !(Topology.blockEntity(level, pos) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = NetworkManager.network(level.getServer(), Topology.networkOf(level, connector));
        if (network == null || !Security.canUse(network, player)) {
            return;
        }
        Identify target;
        if (payload.side().isEmpty()) {
            target = Identify.connector(connector.connectorId(), 0);
        } else {
            GlobalPos device = Devices.global(level, pos.relative(payload.side().get()));
            target = payload.face().isPresent() ? Identify.face(device, payload.face().get(), 0) : Identify.device(device, 0);
        }
        Identifies.toggle(level.getServer(), network, target);
        open(player, pos);
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
                player.serverLevel().getBlockEntity(pos) instanceof ControllerBlockEntity controller ? controller.antennaTier() : 0,
                network.enabled(), io.github.leadassimilator.reticulum.engine.NetworkTicker.isRunning(player.server, network),
                network.switches().redstone().mode(), sheetEntries(player, network), overflowLines(player, network), powerLine(player, network));
    }

    /** "Power: 52,000 / 1,000,000 FE (24 FE/t)", or empty when power is free. */
    private static String powerLine(ServerPlayer player, Network network) {
        var state = io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(network.id()).powerState(player.server, network);
        if (!state.enabled()) {
            return "";
        }
        return Component.translatable(state.powered() ? "screen.reticulum.power" : "screen.reticulum.power_out",
                String.format("%,d", state.stored()), String.format("%,d", state.capacity()), String.format("%.1f", state.upkeep())).getString();
    }

    private static List<Views.SheetEntry> sheetEntries(ServerPlayer player, Network network) {
        var runtime = io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(network.id());
        List<Views.SheetEntry> entries = new ArrayList<>();
        for (io.github.leadassimilator.reticulum.graph.Sheet sheet : network.sheets()) {
            if (sheet.subsheet()) {
                continue;
            }
            entries.add(new Views.SheetEntry(sheet.id(), sheet.name(), sheet.enabled(), sheet.enabled() && !network.runs(sheet),
                    runtime.sheetsOff().contains(sheet.id()),
                    runtime.compiled(sheet, network).errors().size()));
        }
        return entries;
    }

    private static List<String> overflowLines(ServerPlayer player, Network network) {
        List<String> lines = new ArrayList<>();
        network.overflowItems().forEach(stack -> lines.add(stack.getCount() + "× " + stack.getHoverName().getString()));
        network.overflowFluids().forEach(fluid -> lines.add(fluid.getAmount() + " mB " + fluid.getHoverName().getString()));
        network.overflow().chemicals().forEach((id, amount) -> lines.add(amount + " mB " + id));
        return lines;
    }

    /** The controller screen's Network tab: the network's button and redstone mode, and each sheet's button. */
    static void controllerSwitch(ServerPlayer player, Payloads.ControllerSwitch payload) {
        if (!(reachable(player, payload.pos()) instanceof ControllerBlockEntity controller)) {
            return;
        }
        Network network = NetworkManager.network(player.server, controller.networkId());
        if (network == null || !Security.canEdit(network, player)) {
            player.displayClientMessage(Component.translatable("message.reticulum.no_edit_access"), true);
            return;
        }
        switch (payload.what()) {
            case Payloads.ControllerSwitch.NETWORK -> network.setEnabled(payload.value() != 0);
            case Payloads.ControllerSwitch.REDSTONE -> {
                var modes = io.github.leadassimilator.reticulum.graph.Redstone.Mode.values();
                var old = network.switches().redstone();
                var mode = modes[Math.floorMod(payload.value(), modes.length)];
                network.switches().setRedstone(new io.github.leadassimilator.reticulum.graph.Redstone(mode, old.source(), old.device(), old.face(),
                        old.connector(), old.signal()));
            }
            case Payloads.ControllerSwitch.SHEET -> payload.sheet().map(network::sheet).ifPresent(sheet -> sheet.setEnabled(payload.value() != 0));
            default -> {
            }
        }
        NetworkManager.changed(player.server, network);
        open(player, payload.pos());
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
        boolean identified = network != null && be instanceof ConnectorBlockEntity c2
                && Identifies.isOn(network, Identify.connector(c2.connectorId(), 0));
        return new Views.BlockView(pos, connector, network == null ? "" : network.name(), defaultName, customName, sides, canEdit,
                connector && !Topology.isWired(level, pos), identified);
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
                    level.getBlockState(next).getBlock().getName().getString(), "", List.of(), 0);
        }
        if (be instanceof ConnectorBlockEntity connector && Devices.isDevice(level, next, dir.getOpposite())) {
            DeviceRecord record = network == null ? null : network.device(Devices.global(level, next));
            List<String> faces = new ArrayList<>();
            for (FaceKey face : FaceKey.values()) {
                String name = record == null ? null : record.faceName(face);
                faces.add(name == null ? "" : name);
            }
            int identified = 0;
            if (network != null) {
                GlobalPos device = Devices.global(level, next);
                if (Identifies.isOn(network, Identify.device(device, 0))) {
                    identified |= Views.SideView.DEVICE_BIT;
                }
                for (FaceKey face : FaceKey.values()) {
                    if (Identifies.isOn(network, Identify.face(device, face, 0))) {
                        identified |= 1 << face.ordinal();
                    }
                }
            }
            return new Views.SideView(dir, Views.SideKind.DEVICE, Views.JointView.DISCONNECTED, connector.isDeviceEnabled(dir),
                    Devices.icon(level, next), Devices.defaultName(level, next),
                    record == null || record.name() == null ? "" : record.name(), faces, identified);
        }
        return new Views.SideView(dir, Views.SideKind.NONE, Views.JointView.DISCONNECTED, false, ItemStack.EMPTY, "", "", List.of(), 0);
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
