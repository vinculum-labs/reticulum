package io.github.leadassimilator.reticulum.lookat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.engine.MeLinker;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
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
            NetworkRuntime runtime = NetworkTicker.runtime(network.id());
            NetworkRuntime.Reach reach = runtime == null ? null : runtime.reach(connector.connectorId());
            if (reach != null) {
                lines.add(Component.translatable("lookat.reticulum.reach." + reach.key())
                        .withStyle(reach == NetworkRuntime.Reach.OUT_OF_RANGE ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
            List<String> given = new ArrayList<>();
            for (Direction side : Direction.values()) {
                if (connector.power(side) > 0) {
                    given.add(side.getName() + " " + connector.power(side));
                }
            }
            if (!given.isEmpty()) {
                lines.add(Component.translatable("lookat.reticulum.redstone_out", String.join(", ", given)).withStyle(ChatFormatting.RED));
            }
            if (connector.meEndpoints() instanceof MeLinker.Ports ports) {
                meLine(ports, lines);
            }
            if (Identifies.isOn(network, Identify.connector(connector.connectorId(), 0))) {
                lines.add(identifiedLine());
            }
        } else if (be instanceof ControllerBlockEntity controller) {
            lines.add(networkLine(network));
            lines.add(Component.translatable("screen.reticulum.counts", network.connectors().size(), network.devices().size())
                    .withStyle(ChatFormatting.GRAY));
            lines.add(antennaLine(controller.antennaTier()));
            NetworkRuntime runtime = NetworkTicker.runtime(network.id());
            if (runtime != null) {
                NetworkRuntime.PowerState power = runtime.powerState(level.getServer(), network);
                if (power.enabled()) {
                    lines.add(Component.translatable(power.powered() ? "lookat.reticulum.power" : "lookat.reticulum.no_power",
                            power.stored(), power.capacity()).withStyle(power.powered() ? ChatFormatting.GRAY : ChatFormatting.RED));
                }
            }
            if (!network.identified().isEmpty()) {
                lines.add(Component.translatable("lookat.reticulum.identifies", network.identified().size()).withStyle(ChatFormatting.YELLOW));
            }
        } else {
            lines.add(networkLine(network));
        }
    }

    private static void describeDevice(ServerLevel level, BlockPos pos, @Nullable Direction face, Player player, List<Component> lines) {
        GlobalPos global = Devices.global(level, pos);
        boolean activeSomewhere = false;
        for (Network network : NetworkManager.data(level.getServer()).networks()) {
            DeviceRecord record = network.device(global);
            if (record != null && record.state() == DeviceRecord.State.ACTIVE && Security.canView(network, player)) {
                activeSomewhere = true;
            }
        }
        for (Network network : NetworkManager.data(level.getServer()).networks()) {
            DeviceRecord record = network.device(global);
            if (record == null || !Security.canView(network, player)
                    || activeSomewhere && record.state() != DeviceRecord.State.ACTIVE) {
                continue;
            }
            lines.add(Component.literal(Devices.displayName(record, level, pos)).withStyle(ChatFormatting.WHITE));
            String faceName = face == null ? null : record.faceName(FaceKey.of(face));
            if (faceName != null) {
                lines.add(Component.translatable("lookat.reticulum.face", faceName).withStyle(ChatFormatting.GRAY));
            }
            lines.add(networkLine(network));
            if (record.state() != DeviceRecord.State.ACTIVE) {
                lines.add(Component.translatable("lookat.reticulum.state." + record.state().getSerializedName()).withStyle(ChatFormatting.GOLD));
            }
            NetworkRuntime runtime = NetworkTicker.runtime(network.id());
            if (runtime != null && runtime.outOfRange(global)) {
                lines.add(Component.translatable("lookat.reticulum.device_out_of_range").withStyle(ChatFormatting.RED));
            }
            int given = givenRedstone(level, pos);
            if (given > 0) {
                lines.add(Component.translatable("lookat.reticulum.redstone_in", given).withStyle(ChatFormatting.RED));
            }
            if (Identifies.isOn(network, Identify.device(global, 0))
                    || face != null && Identifies.isOn(network, Identifies.deviceOrFace(global, face))) {
                lines.add(identifiedLine());
            }
        }
    }

    /**
     * Per ME port, the fullest cable on its way to the ME controller ("north 3/8"): AE2's own cable tooltips count
     * only the channels routed through that one cable, so a cable past a link can read 0 while the way back is full.
     */
    private static void meLine(MeLinker.Ports ports, List<Component> lines) {
        List<String> parts = new ArrayList<>();
        boolean full = false;
        for (Map.Entry<Direction, MeLinker.Usage> entry : ports.usages().entrySet()) {
            MeLinker.Usage usage = entry.getValue();
            if (usage.connected() && usage.max() > 0) {
                parts.add(entry.getKey().getName() + " " + usage.used() + "/" + usage.max());
                full |= usage.used() >= usage.max();
            }
        }
        if (!parts.isEmpty()) {
            lines.add(Component.translatable("lookat.reticulum.me", String.join(", ", parts))
                    .withStyle(full ? ChatFormatting.RED : ChatFormatting.GRAY));
        }
    }

    /** The strongest redstone a connector gives this block. */
    private static int givenRedstone(ServerLevel level, BlockPos pos) {
        int best = 0;
        for (Direction side : Direction.values()) {
            if (level.isLoaded(pos.relative(side)) && level.getBlockEntity(pos.relative(side)) instanceof ConnectorBlockEntity connector) {
                best = Math.max(best, connector.power(side.getOpposite()));
            }
        }
        return best;
    }

    private static Component antennaLine(int tier) {
        return switch (tier) {
            case 0 -> Component.translatable("lookat.reticulum.antenna_none").withStyle(ChatFormatting.GRAY);
            case 1 -> Component.translatable("lookat.reticulum.antenna_1", ReticulumConfig.ANTENNA_RANGE.getAsInt()).withStyle(ChatFormatting.GRAY);
            case 2 -> Component.translatable("lookat.reticulum.antenna_2").withStyle(ChatFormatting.GRAY);
            default -> Component.translatable("lookat.reticulum.antenna_3").withStyle(ChatFormatting.GRAY);
        };
    }

    private static Component identifiedLine() {
        return Component.translatable("lookat.reticulum.identified").withStyle(ChatFormatting.YELLOW);
    }

    private static Component networkLine(Network network) {
        // Color alone marks the network line; device names are white, the network is aqua.
        return Component.literal(network.name()).withStyle(ChatFormatting.AQUA);
    }

}
