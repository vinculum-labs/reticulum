package io.github.leadassimilator.reticulum.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.block.NetworkBlock;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.ConnectorRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cable topology. Rules (see the design spec, "Keeping cable lines apart"):
 * <ul>
 * <li>Two network blocks are connected when neither side is disconnected and their networks are compatible
 * (equal, or at least one has none).</li>
 * <li>A new block takes the network of the block it was placed against; neighbors of any other network,
 * or that the player may not use, are disconnected automatically.</li>
 * <li>Network ids are sticky: losing the controller or unloading part of a network never reassigns them.</li>
 * </ul>
 */
public final class Topology {
    /** Upper bound on one flood fill, so a runaway structure can't stall the server. */
    private static final int MAX_COMPONENT_SIZE = 200_000;

    private Topology() {
    }

    /** A connected set of network blocks (one cable section). */
    public record Section(Set<BlockPos> blocks, Set<UUID> networks, List<BlockPos> controllers) {
        @Nullable
        public UUID network() {
            return networks.size() == 1 ? networks.iterator().next() : null;
        }
    }

    /** What topology needs to know about a block after it has been removed. */
    public record Removed(@Nullable UUID networkId, boolean controller, @Nullable UUID connectorId) {
    }

    public static Removed capture(NetworkBlockEntity be) {
        return new Removed(be.networkId(), be instanceof ControllerBlockEntity,
                be instanceof ConnectorBlockEntity connector ? connector.connectorId() : null);
    }

    // ---------------------------------------------------------------- queries

    @Nullable
    public static NetworkBlockEntity blockEntity(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof NetworkBlockEntity be ? be : null;
    }

    /** The block's network, or null if it has none or the network no longer exists. */
    @Nullable
    public static UUID networkOf(ServerLevel level, NetworkBlockEntity be) {
        UUID id = be.networkId();
        return id != null && NetworkManager.network(level.getServer(), id) != null ? id : null;
    }

    @Nullable
    public static Network network(ServerLevel level, BlockPos pos) {
        NetworkBlockEntity be = blockEntity(level, pos);
        return be == null ? null : NetworkManager.network(level.getServer(), networkOf(level, be));
    }

    private static boolean compatible(@Nullable UUID a, @Nullable UUID b) {
        return a == null || b == null || a.equals(b);
    }

    public enum JointState {
        CONNECTED,
        DISCONNECTED,
        /** Not disconnected by hand, but the two sides belong to different networks. */
        CONFLICT
    }

    @Nullable
    public static JointState jointState(ServerLevel level, BlockPos pos, Direction side) {
        NetworkBlockEntity a = blockEntity(level, pos);
        NetworkBlockEntity b = blockEntity(level, pos.relative(side));
        if (a == null || b == null) {
            return null;
        }
        boolean differentNetworks = !compatible(networkOf(level, a), networkOf(level, b));
        if (a.isDisconnected(side) || b.isDisconnected(side.getOpposite())) {
            return differentNetworks ? JointState.CONFLICT : JointState.DISCONNECTED;
        }
        return differentNetworks ? JointState.CONFLICT : JointState.CONNECTED;
    }

    public static boolean connected(ServerLevel level, BlockPos pos, Direction side) {
        return jointState(level, pos, side) == JointState.CONNECTED;
    }

    /** Flood fill over connected sides. {@code exclude} is treated as already visited. */
    public static Section component(ServerLevel level, BlockPos start, @Nullable BlockPos exclude) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        Set<UUID> networks = new HashSet<>();
        List<BlockPos> controllers = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        if (exclude != null) {
            visited.add(exclude);
        }
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            NetworkBlockEntity be = blockEntity(level, pos);
            if (be == null) {
                continue;
            }
            UUID net = networkOf(level, be);
            if (net != null) {
                networks.add(net);
            }
            if (be instanceof ControllerBlockEntity) {
                controllers.add(pos);
            }
            if (visited.size() > MAX_COMPONENT_SIZE) {
                Reticulum.LOGGER.warn("Reticulum network at {} exceeds {} blocks; stopping flood fill", start, MAX_COMPONENT_SIZE);
                break;
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (!visited.contains(next) && connected(level, pos, dir)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        if (exclude != null) {
            visited.remove(exclude);
        }
        return new Section(visited, networks, controllers);
    }

    // ---------------------------------------------------------------- placement and removal

    public static void onPlaced(ServerLevel level, BlockPos pos, @Nullable Direction placedAgainst, @Nullable ServerPlayer player) {
        NetworkBlockEntity be = blockEntity(level, pos);
        if (be == null || be.isRegistered()) {
            return;
        }
        be.setRegistered();

        UUID own = be instanceof ControllerBlockEntity controller ? claimControllerNetwork(level, pos, controller, player) : null;

        // The component behind each neighbor, as it was before this block arrived.
        Map<Direction, Section> sides = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            NetworkBlockEntity neighbor = blockEntity(level, next);
            if (neighbor == null || be.isDisconnected(dir) || neighbor.isDisconnected(dir.getOpposite())) {
                continue;
            }
            Section comp = component(level, next, pos);
            UUID net = comp.network();
            if (net != null && player != null) {
                Network network = NetworkManager.network(level.getServer(), net);
                if (network != null && !Security.canUse(network, player)) {
                    disconnectAutomatically(be, neighbor, dir);
                    player.displayClientMessage(Component.translatable("message.reticulum.no_access", network.name()), true);
                    continue;
                }
            }
            sides.put(dir, comp);
        }

        UUID primary = own;
        boolean ambiguous = false;
        if (primary == null) {
            Section against = placedAgainst == null ? null : sides.get(placedAgainst);
            if (against != null && against.network() != null) {
                primary = against.network();
            } else {
                Set<UUID> distinct = new HashSet<>();
                sides.values().forEach(c -> distinct.addAll(c.networks()));
                if (distinct.size() == 1) {
                    primary = distinct.iterator().next();
                } else {
                    ambiguous = distinct.size() > 1;
                }
            }
        }

        for (Map.Entry<Direction, Section> side : sides.entrySet()) {
            Section comp = side.getValue();
            UUID net = comp.network();
            boolean connect = primary != null ? net == null || net.equals(primary) : net == null;
            if (own != null && !comp.controllers().isEmpty()) {
                connect = false;
            }
            if (!connect) {
                NetworkBlockEntity neighbor = blockEntity(level, pos.relative(side.getKey()));
                if (neighbor != null) {
                    disconnectAutomatically(be, neighbor, side.getKey());
                }
            }
        }
        if (ambiguous && player != null) {
            player.displayClientMessage(Component.translatable("message.reticulum.ambiguous_placement"), true);
        }

        Section comp = component(level, pos, null);
        assign(level, comp, decideNetwork(level, comp, primary));
        if (be instanceof ConnectorBlockEntity) {
            rescanConnector(level, pos);
        }
        refreshAround(level, comp.blocks());
        refreshAround(level, Set.of(pos));
    }

    private static void disconnectAutomatically(NetworkBlockEntity be, NetworkBlockEntity neighbor, Direction dir) {
        be.setAutoDisconnected(dir, true);
        neighbor.setAutoDisconnected(dir.getOpposite(), true);
    }

    /** Finds or creates the controller's network. Returns its id. */
    private static UUID claimControllerNetwork(ServerLevel level, BlockPos pos, ControllerBlockEntity controller, @Nullable ServerPlayer player) {
        GlobalPos here = Devices.global(level, pos);
        UUID wanted = controller.takeItemNetworkId();
        if (wanted == null) {
            // Placed without an item (e.g. from a structure): keep whatever the block entity already had.
            wanted = controller.networkId();
        }
        Network network = NetworkManager.network(level.getServer(), wanted);
        if (network != null) {
            GlobalPos existing = network.controller();
            if (existing != null && !existing.equals(here) && controllerStillThere(level, existing, network.id())) {
                message(player, Component.translatable("message.reticulum.controller_exists", network.name()));
                network = null;
            } else if (player != null && !Security.canAdmin(network, player)) {
                message(player, Component.translatable("message.reticulum.no_access", network.name()));
                network = null;
            }
        } else if (wanted != null) {
            message(player, Component.translatable("message.reticulum.network_gone"));
        }
        if (network == null) {
            network = NetworkManager.create(level.getServer(), player);
        }
        network.setController(here);
        controller.setNetworkId(network.id());
        NetworkManager.changed(level.getServer(), network);
        return network.id();
    }

    private static boolean controllerStillThere(ServerLevel level, GlobalPos pos, UUID networkId) {
        ServerLevel other = level.getServer().getLevel(pos.dimension());
        if (other == null) {
            return false;
        }
        if (!other.isLoaded(pos.pos())) {
            return true;
        }
        return other.getBlockEntity(pos.pos()) instanceof ControllerBlockEntity c && networkId.equals(c.networkId());
    }

    @Nullable
    private static UUID decideNetwork(ServerLevel level, Section comp, @Nullable UUID preferred) {
        if (comp.controllers().size() > 1) {
            Reticulum.LOGGER.warn("Reticulum component with {} controllers at {}", comp.controllers().size(), comp.controllers());
        }
        if (!comp.controllers().isEmpty()) {
            NetworkBlockEntity controller = blockEntity(level, comp.controllers().getFirst());
            if (controller != null) {
                return networkOf(level, controller);
            }
        }
        if (preferred != null) {
            return preferred;
        }
        return comp.network();
    }

    public static void onRemoved(ServerLevel level, BlockPos pos, Removed removed) {
        Network network = NetworkManager.network(level.getServer(), removed.networkId());
        if (network != null) {
            if (removed.connectorId() != null) {
                NetworkManager.removeConnector(level.getServer(), network, removed.connectorId());
            }
            if (removed.controller() && Devices.global(level, pos).equals(network.controller())) {
                network.setController(null);
                NetworkManager.changed(level.getServer(), network);
            }
        }
        // Automatic disconnects only existed because of the removed block; a new block there starts fresh.
        for (Direction dir : Direction.values()) {
            NetworkBlockEntity neighbor = blockEntity(level, pos.relative(dir));
            if (neighbor != null && !neighbor.isManuallyDisconnected(dir.getOpposite())) {
                neighbor.setAutoDisconnected(dir.getOpposite(), false);
            }
        }
    }

    /** Sets the network of every block in the component, moving connectors between networks as needed. */
    private static void assign(ServerLevel level, Section comp, @Nullable UUID networkId) {
        for (BlockPos pos : comp.blocks()) {
            NetworkBlockEntity be = blockEntity(level, pos);
            if (be == null || be instanceof ControllerBlockEntity) {
                continue;
            }
            UUID old = networkOf(level, be);
            if (Objects.equals(old, networkId)) {
                continue;
            }
            be.setNetworkId(networkId);
            if (be instanceof ConnectorBlockEntity connector) {
                Network oldNetwork = NetworkManager.network(level.getServer(), old);
                if (oldNetwork != null) {
                    NetworkManager.removeConnector(level.getServer(), oldNetwork, connector.connectorId());
                }
                rescanConnector(level, pos);
            }
        }
    }

    // ---------------------------------------------------------------- connectors

    public static List<GlobalPos> scanDevices(ServerLevel level, BlockPos pos, ConnectorBlockEntity connector) {
        List<GlobalPos> devices = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (connector.isDeviceEnabled(dir) && Devices.isDevice(level, next)) {
                devices.add(Devices.global(level, next));
            }
        }
        return devices;
    }

    /** Brings the connector's record in its network up to date with the devices it reaches now. */
    public static void rescanConnector(ServerLevel level, BlockPos pos) {
        if (!(blockEntity(level, pos) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        Network network = NetworkManager.network(level.getServer(), networkOf(level, connector));
        if (network == null) {
            return;
        }
        List<GlobalPos> devices = scanDevices(level, pos, connector);
        ConnectorRecord record = network.connector(connector.connectorId());
        if (record != null && record.devices().equals(devices) && record.pos().equals(Devices.global(level, pos))) {
            return;
        }
        NetworkManager.updateConnector(level, network, connector.connectorId(), Devices.global(level, pos), devices);
    }

    // ---------------------------------------------------------------- player actions

    /**
     * Disconnects a connected joint, or connects a disconnected or conflicting one. A connector side facing a device is attached or detached
     * instead. Returns the message to show the player.
     */
    public static Component toggle(ServerLevel level, BlockPos pos, Direction side, @Nullable ServerPlayer player) {
        NetworkBlockEntity a = blockEntity(level, pos);
        if (a == null) {
            return Component.empty();
        }
        BlockPos otherPos = pos.relative(side);
        NetworkBlockEntity b = blockEntity(level, otherPos);
        if (b == null) {
            if (a instanceof ConnectorBlockEntity connector && Devices.isDevice(level, otherPos)) {
                return toggleDevice(level, pos, connector, side, player);
            }
            return Component.translatable("message.reticulum.nothing_to_connect");
        }
        Network netA = NetworkManager.network(level.getServer(), networkOf(level, a));
        Network netB = NetworkManager.network(level.getServer(), networkOf(level, b));
        if (player != null && (netA != null && !Security.canEdit(netA, player) || netB != null && !Security.canEdit(netB, player))) {
            return Component.translatable("message.reticulum.no_edit_access");
        }

        if (connected(level, pos, side)) {
            a.setManuallyDisconnected(side, true);
            b.setManuallyDisconnected(side.getOpposite(), true);
            refreshAround(level, Set.of(pos, otherPos));
            return Component.translatable("message.reticulum.disconnected");
        }

        Section compA = component(level, pos, null);
        Section compB = component(level, otherPos, null);
        UUID target;
        if (compA.blocks().contains(otherPos)) {
            target = compA.network();
        } else {
            UUID na = compA.network();
            UUID nb = compB.network();
            boolean ctrlA = !compA.controllers().isEmpty();
            boolean ctrlB = !compB.controllers().isEmpty();
            if (ctrlA && ctrlB) {
                return Component.translatable("message.reticulum.two_controllers");
            }
            if (compatible(na, nb)) {
                target = na != null ? na : nb;
            } else if (!ctrlB) {
                target = na;
            } else if (!ctrlA) {
                target = nb;
            } else {
                return Component.translatable("message.reticulum.two_controllers");
            }
        }
        a.clearDisconnects(side);
        b.clearDisconnects(side.getOpposite());
        assign(level, compA, target);
        assign(level, compB, target);
        Set<BlockPos> changed = new HashSet<>(compA.blocks());
        changed.addAll(compB.blocks());
        refreshAround(level, changed);
        return Component.translatable("message.reticulum.connected");
    }

    private static Component toggleDevice(ServerLevel level, BlockPos pos, ConnectorBlockEntity connector, Direction side, @Nullable ServerPlayer player) {
        Network network = NetworkManager.network(level.getServer(), networkOf(level, connector));
        if (player != null && network != null && !Security.canEdit(network, player)) {
            return Component.translatable("message.reticulum.no_edit_access");
        }
        boolean enable = !connector.isDeviceEnabled(side);
        connector.setDeviceEnabled(side, enable);
        rescanConnector(level, pos);
        refreshState(level, pos);
        return Component.translatable(enable ? "message.reticulum.device_attached" : "message.reticulum.device_detached");
    }

    /** Inset of the core from each edge of the block, in blocks. */
    private static double coreInset(boolean connector) {
        return connector ? 4 / 16.0 : 6 / 16.0;
    }

    /** The core of a cable or connector, in block-local coordinates. */
    public static AABB coreBox(boolean connector) {
        double inset = coreInset(connector);
        return new AABB(inset, inset, inset, 1 - inset, 1 - inset, 1 - inset);
    }

    /** The joint (arm) running from the core out to one side, in block-local coordinates. */
    public static AABB jointBox(Direction side, boolean connector) {
        double inset = coreInset(connector);
        double lo = connector ? 5 / 16.0 : 6 / 16.0;
        double hi = 1 - lo;
        double[] min = {lo, lo, lo};
        double[] max = {hi, hi, hi};
        int axis = side.getAxis().ordinal();
        if (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[axis] = 1 - inset;
            max[axis] = 1;
        } else {
            min[axis] = 0;
            max[axis] = inset;
        }
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * The side the player is aiming at on a cable or connector. The hitbox is a full block, so this traces the
     * look ray against the rendered shape instead: the arms (whether present or not) and the core. Hitting an arm
     * means that arm's side; hitting the core means the core face hit. When the ray only passes through empty space
     * beside the shape, the arm or core face closest to the ray wins.
     */
    public static Direction targetSide(BlockPos pos, boolean connector, Vec3 from, Vec3 to, Direction fallback) {
        Direction exact = firstHit(pos, connector, from, to);
        if (exact != null) {
            return exact;
        }
        Optional<Vec3> enter = new AABB(pos).clip(from, to);
        Optional<Vec3> exit = new AABB(pos).clip(to, from);
        if (enter.isEmpty() || exit.isEmpty()) {
            return fallback;
        }
        Vec3 origin = Vec3.atLowerCornerOf(pos);
        Vec3 a = enter.get().subtract(origin);
        Vec3 b = exit.get().subtract(origin);
        Direction best = fallback;
        double bestDistance = Double.MAX_VALUE;
        final int samples = 48;
        for (int i = 0; i <= samples; i++) {
            Vec3 p = a.lerp(b, i / (double) samples);
            for (Direction dir : Direction.values()) {
                double d = distanceSqr(jointBox(dir, connector), p);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = dir;
                }
            }
            double d = distanceSqr(coreBox(connector), p);
            if (d < bestDistance) {
                bestDistance = d;
                best = Direction.getNearest(p.x - 0.5, p.y - 0.5, p.z - 0.5);
            }
        }
        return best;
    }

    /** The first arm or core face the ray actually hits, nearest the eye. */
    @Nullable
    private static Direction firstHit(BlockPos pos, boolean connector, Vec3 from, Vec3 to) {
        Direction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            Optional<Vec3> point = jointBox(dir, connector).move(pos).clip(from, to);
            if (point.isPresent() && from.distanceToSqr(point.get()) < bestDistance) {
                bestDistance = from.distanceToSqr(point.get());
                best = dir;
            }
        }
        BlockHitResult core = AABB.clip(List.of(coreBox(connector)), from, to, pos);
        if (core != null && from.distanceToSqr(core.getLocation()) < bestDistance) {
            best = core.getDirection();
        }
        return best;
    }

    private static double distanceSqr(AABB box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------------------------------------------------------------- block states

    public static BlockState computeState(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof NetworkBlock block) || block.linkProperties().isEmpty()) {
            return state;
        }
        NetworkBlockEntity be = blockEntity(level, pos);
        if (be == null) {
            return state;
        }
        for (Map.Entry<Direction, EnumProperty<Link>> entry : block.linkProperties().entrySet()) {
            state = state.setValue(entry.getValue(), link(level, pos, be, entry.getKey(), entry.getValue()));
        }
        return state;
    }

    private static Link link(ServerLevel level, BlockPos pos, NetworkBlockEntity be, Direction side, EnumProperty<Link> property) {
        BlockPos next = pos.relative(side);
        if (blockEntity(level, next) != null) {
            // A disconnected or conflicting joint renders as if nothing were there.
            return connected(level, pos, side) ? Link.CABLE : Link.NONE;
        }
        if (be instanceof ConnectorBlockEntity connector && property.getPossibleValues().contains(Link.DEVICE)
                && connector.isDeviceEnabled(side) && Devices.isDevice(level, next)) {
            return Link.DEVICE;
        }
        return Link.NONE;
    }

    public static void refreshState(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        BlockState updated = computeState(level, pos, state);
        if (updated != state) {
            level.setBlock(pos, updated, Block.UPDATE_ALL);
        }
    }

    /** Refreshes the given blocks and their neighbors, whose caps may have changed. */
    public static void refreshAround(ServerLevel level, Set<BlockPos> blocks) {
        Set<BlockPos> all = new LinkedHashSet<>(blocks);
        for (BlockPos pos : blocks) {
            for (Direction dir : Direction.values()) {
                all.add(pos.relative(dir));
            }
        }
        for (BlockPos pos : all) {
            if (blockEntity(level, pos) != null) {
                refreshState(level, pos);
            }
        }
    }

    private static void message(@Nullable ServerPlayer player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }
}
