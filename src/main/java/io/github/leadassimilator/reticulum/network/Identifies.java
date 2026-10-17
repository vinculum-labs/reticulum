package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
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

    /** The longest label sent with a marker (the packet's limit). */
    private static final int MAX_LABEL = 256;
    /** The most markers sent to one player (the packet's limit). */
    private static final int MAX_MARKERS = 4096;

    /**
     * Turns an identify on (with the given color, or the next palette color when 0) or off. Returns whether it is on
     * now: turning on is refused for something that isn't part of the network, or when {@link Limits#IDENTIFIES}
     * things are already lit.
     */
    public static boolean set(MinecraftServer server, Network network, Identify target, boolean on) {
        List<Identify> list = network.identified();
        if (on && (!belongs(network, target) || list.size() >= Limits.IDENTIFIES && !isOn(network, target))) {
            return false;
        }
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

    /**
     * Turns several on or off at once, telling players once: only those not so already change (one already lit keeps its
     * color), and turning on stops at {@link Limits#IDENTIFIES}. Returns the ones that changed.
     */
    public static List<Identify> setAll(MinecraftServer server, Network network, List<Identify> targets, boolean on) {
        List<Identify> list = network.identified();
        List<Identify> changed = new ArrayList<>();
        for (Identify target : targets) {
            if (isOn(network, target) == on) {
                continue;
            }
            if (on) {
                if (!belongs(network, target) || list.size() >= Limits.IDENTIFIES) {
                    continue;
                }
                int color = target.color() != 0 ? target.color() : Identify.PALETTE[list.size() % Identify.PALETTE.length];
                list.add(new Identify(target.kind(), target.connector(), target.device(), target.face(), color, System.currentTimeMillis()));
            } else {
                list.removeIf(target::sameTarget);
            }
            changed.add(target);
        }
        if (!changed.isEmpty()) {
            changed(server, network);
        }
        return changed;
    }

    /** Whether an identify's connector or device is part of the network (only those can be lit). */
    public static boolean belongs(Network network, Identify target) {
        return switch (target.kind()) {
            case CONNECTOR -> target.connector().map(network::connector).isPresent();
            case DEVICE -> target.device().map(network::device).isPresent();
            case FACE -> target.face().isPresent() && target.device().map(network::device).isPresent();
        };
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
            if (network.identified().isEmpty() || !Security.could(network, player, Role.VIEWER)) {
                continue;
            }
            for (Identify identify : network.identified()) {
                Payloads.IdentifyMarker marker = marker(player.server, network, identify);
                if (marker != null && markers.size() < MAX_MARKERS) {
                    markers.add(new Payloads.IdentifyMarker(marker.pos(), marker.face(), marker.color(), clip(marker.label())));
                }
            }
        }
        return markers;
    }

    @Nullable
    private static Payloads.IdentifyMarker marker(MinecraftServer server, Network network, Identify identify) {
        String label = label(server, network, identify);
        return switch (identify.kind()) {
            case CONNECTOR -> identify.connector().map(network::connector)
                    .map(record -> new Payloads.IdentifyMarker(record.pos(), Optional.empty(), identify.color(), label))
                    .orElse(null);
            case DEVICE -> identify.device()
                    .map(pos -> new Payloads.IdentifyMarker(pos, Optional.empty(), identify.color(), label))
                    .orElse(null);
            case FACE -> identify.device().isEmpty() || identify.face().isEmpty() ? null
                    : new Payloads.IdentifyMarker(identify.device().get(), Optional.ofNullable(identify.face().get().direction()), identify.color(), label);
        };
    }

    private static String clip(String label) {
        return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label;
    }

    /** What an identify is called everywhere: the connector's or device's name, and for a face, the face's too. */
    public static String label(MinecraftServer server, Network network, Identify identify) {
        return switch (identify.kind()) {
            case CONNECTOR -> identify.connector().map(network::connector).map(ConnectorRecord::displayName).orElse("Missing connector");
            case DEVICE -> identify.device().map(pos -> deviceName(server, network, pos)).orElse("?");
            case FACE -> identify.device().map(pos -> deviceName(server, network, pos)).orElse("?") + " › "
                    + identify.face().map(face -> faceName(network.device(identify.device().orElse(null)), face)).orElse("?");
        };
    }

    /** A face's own name, else its side. */
    public static String faceName(@Nullable DeviceRecord record, FaceKey face) {
        return record != null && record.faceName(face) != null ? record.faceName(face) : face.getSerializedName();
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
        return record != null ? Devices.defaultName(Component.literal(record.blockId().getPath()), pos.pos())
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
     * Every 5 seconds: turns off identifies older than the server's auto-off time (if set) or no longer part of their
     * network, and refreshes what
     * players see (names change, access changes, devices get unloaded or come back).
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 100 != 0) {
            return;
        }
        // Lit things that are no longer part of the network (broken, moved) go out too, so they don't stay in the save.
        long autoOff = ReticulumConfig.IDENTIFY_AUTO_OFF_MINUTES.getAsInt() * 60_000L;
        long now = System.currentTimeMillis();
        for (Network network : NetworkManager.data(server).networks()) {
            boolean removed = false;
            for (Iterator<Identify> it = network.identified().iterator(); it.hasNext();) {
                Identify identify = it.next();
                if (autoOff > 0 && now - identify.since() > autoOff || !belongs(network, identify)) {
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                NetworkManager.changed(server, network);
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
