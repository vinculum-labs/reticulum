package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turning identifies on and off, and telling players what to light up: every player sees what is lit on the
 * networks they can view.
 */
public final class Identifies {
    private Identifies() {
    }

    /** Turns an identify on (with the given color, or the next palette color when 0) or off. Returns whether it is on now. */
    public static boolean set(MinecraftServer server, Network network, Identify target, boolean on) {
        List<Identify> list = network.identified();
        boolean was = list.removeIf(target::sameTarget);
        if (on) {
            int color = target.color() != 0 ? target.color() : Identify.PALETTE[list.size() % Identify.PALETTE.length];
            list.add(new Identify(target.kind(), target.connector(), target.device(), target.face(), color, System.currentTimeMillis()));
        }
        if (was || on) {
            changed(server, network);
        }
        return on;
    }

    public static boolean isOn(Network network, Identify target) {
        return network.identified().stream().anyMatch(target::sameTarget);
    }

    /** Flips an identify; returns whether it is on now. */
    public static boolean toggle(MinecraftServer server, Network network, Identify target) {
        return set(server, network, target, !isOn(network, target));
    }

    public static void clear(MinecraftServer server, Network network) {
        if (!network.identified().isEmpty()) {
            network.identified().clear();
            changed(server, network);
        }
    }

    private static void changed(MinecraftServer server, Network network) {
        NetworkManager.changed(server, network);
        syncAll(server);
    }

    // ---------------------------------------------------------------- telling players

    /** What one player should see lit up: everything identified on the networks they can view. */
    public static List<Payloads.IdentifyMarker> markersFor(ServerPlayer player) {
        List<Payloads.IdentifyMarker> markers = new ArrayList<>();
        for (Network network : NetworkManager.data(player.server).networks()) {
            if (network.identified().isEmpty() || !Security.canView(network, player)) {
                continue;
            }
            for (Identify identify : network.identified()) {
                Payloads.IdentifyMarker marker = marker(player.server, network, identify);
                if (marker != null) {
                    markers.add(marker);
                }
            }
        }
        return markers;
    }

    @Nullable
    private static Payloads.IdentifyMarker marker(MinecraftServer server, Network network, Identify identify) {
        return switch (identify.kind()) {
            case CONNECTOR -> {
                ConnectorRecord record = identify.connector().map(network::connector).orElse(null);
                if (record == null) {
                    yield null;
                }
                String name = record.name() != null ? record.name()
                        : "Connector (" + record.pos().pos().getX() + ", " + record.pos().pos().getY() + ", " + record.pos().pos().getZ() + ")";
                yield new Payloads.IdentifyMarker(record.pos(), Optional.empty(), identify.color(), name);
            }
            case DEVICE -> identify.device()
                    .map(pos -> new Payloads.IdentifyMarker(pos, Optional.empty(), identify.color(), deviceName(server, network, pos)))
                    .orElse(null);
            case FACE -> {
                if (identify.device().isEmpty() || identify.face().isEmpty()) {
                    yield null;
                }
                GlobalPos pos = identify.device().get();
                FaceKey face = identify.face().get();
                DeviceRecord record = network.device(pos);
                String faceName = record != null && record.faceName(face) != null ? record.faceName(face) : face.getSerializedName();
                yield new Payloads.IdentifyMarker(pos, Optional.ofNullable(face.direction()), identify.color(),
                        deviceName(server, network, pos) + " › " + faceName);
            }
        };
    }

    public static String deviceName(MinecraftServer server, Network network, GlobalPos pos) {
        DeviceRecord record = network.device(pos);
        if (record != null && record.name() != null) {
            return record.name();
        }
        ServerLevel level = server.getLevel(pos.dimension());
        if (level != null && level.isLoaded(pos.pos())) {
            return Devices.defaultName(level, pos.pos());
        }
        return record != null ? Devices.defaultName(net.minecraft.network.chat.Component.literal(record.blockId().getPath()), pos.pos())
                : pos.pos().toShortString();
    }

    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new Payloads.IdentifyMarkers(markersFor(player)));
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player);
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    /**
     * Every 5 seconds: turns off identifies older than the server's auto-off time (if set), and refreshes what
     * players see (names change, access changes, devices get unloaded or come back).
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 100 != 0) {
            return;
        }
        long autoOff = ReticulumConfig.IDENTIFY_AUTO_OFF_MINUTES.getAsInt() * 60_000L;
        if (autoOff > 0) {
            long now = System.currentTimeMillis();
            for (Network network : NetworkManager.data(server).networks()) {
                boolean removed = false;
                for (Iterator<Identify> it = network.identified().iterator(); it.hasNext();) {
                    if (now - it.next().since() > autoOff) {
                        it.remove();
                        removed = true;
                    }
                }
                if (removed) {
                    NetworkManager.changed(server, network);
                }
            }
        }
        boolean any = NetworkManager.data(server).networks().stream().anyMatch(n -> !n.identified().isEmpty());
        if (any || lastSyncHadMarkers) {
            syncAll(server);
            lastSyncHadMarkers = any;
        }
    }

    private static boolean lastSyncHadMarkers = true;

    /** The target of an identify for a face of a device, given a direction (null: the whole device). */
    public static Identify deviceOrFace(GlobalPos device, @Nullable Direction face) {
        return face == null ? Identify.device(device, 0) : Identify.face(device, FaceKey.of(face), 0);
    }
}
