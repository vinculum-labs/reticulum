package io.github.leadassimilator.reticulum.lookat;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.Security;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * The lines shown when looking at a network block or a device. Shared by the Jade and The One Probe
 * integrations and the built-in HUD, and computed on the server so they respect security.
 */
public final class LookAt {
    private LookAt() {
    }

    public static List<Component> describe(ServerLevel level, BlockPos pos, @Nullable Direction face, Player player) {
        List<Component> lines = new ArrayList<>();
        NetworkBlockEntity be = Topology.blockEntity(level, pos);
        if (be != null) {
            describeNetworkBlock(level, be, player, lines);
        } else {
            describeDevice(level, pos, face, player, lines);
        }
        return lines;
    }

    private static void describeNetworkBlock(ServerLevel level, NetworkBlockEntity be, Player player, List<Component> lines) {
        Network network = NetworkManager.network(level.getServer(), Topology.networkOf(level, be));
        if (network == null) {
            lines.add(Component.translatable("lookat.reticulum.no_network").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!Security.canView(network, player)) {
            lines.add(Component.translatable("lookat.reticulum.private").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (be instanceof ConnectorBlockEntity connector) {
            ConnectorRecord record = network.connector(connector.connectorId());
            if (record != null && record.name() != null) {
                lines.add(Component.literal(record.name()).withStyle(ChatFormatting.WHITE));
            }
            lines.add(networkLine(network));
            lines.add(Component.translatable("lookat.reticulum.devices", record == null ? 0 : record.devices().size())
                    .withStyle(ChatFormatting.GRAY));
        } else if (be instanceof ControllerBlockEntity) {
            lines.add(networkLine(network));
            lines.add(Component.translatable("screen.reticulum.counts", network.connectors().size(), network.devices().size())
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(networkLine(network));
        }
    }

    private static void describeDevice(ServerLevel level, BlockPos pos, @Nullable Direction face, Player player, List<Component> lines) {
        GlobalPos global = Devices.global(level, pos);
        for (Network network : NetworkManager.data(level.getServer()).networks()) {
            DeviceRecord record = network.device(global);
            if (record == null || !Security.canView(network, player)) {
                continue;
            }
            lines.add(Component.literal(Devices.displayName(record, level, pos)).withStyle(ChatFormatting.WHITE));
            String faceName = face == null ? null : record.faceName(faceKey(face));
            if (faceName != null) {
                lines.add(Component.translatable("lookat.reticulum.face", faceName).withStyle(ChatFormatting.GRAY));
            }
            lines.add(networkLine(network));
            if (record.state() != DeviceRecord.State.ACTIVE) {
                lines.add(Component.translatable("lookat.reticulum.state." + record.state().getSerializedName()).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static Component networkLine(Network network) {
        return Component.translatable("lookat.reticulum.network", network.name()).withStyle(ChatFormatting.AQUA);
    }

    private static FaceKey faceKey(Direction face) {
        return switch (face) {
            case DOWN -> FaceKey.DOWN;
            case UP -> FaceKey.UP;
            case NORTH -> FaceKey.NORTH;
            case SOUTH -> FaceKey.SOUTH;
            case WEST -> FaceKey.WEST;
            case EAST -> FaceKey.EAST;
        };
    }
}
