package io.github.leadassimilator.reticulum.integration.ae2;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import appeng.api.AECapabilities;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.engine.MeLinker;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * A connector's ME endpoints: one AE2 grid node per side that faces an AE2 cable or machine, exposed on that side
 * only, so four dense cables on four sides are four separate 32-channel ME ports. Nodes carry channels like a dense
 * cable (they need none themselves); ME link wires connect them to endpoints elsewhere.
 */
public final class MeEndpoints implements IInWorldGridNodeHost, MeLinker.Ports {
    private static final IGridNodeListener<MeEndpoints> LISTENER = (owner, node) -> owner.connector.setChanged();

    private final ConnectorBlockEntity connector;
    private final Map<Direction, IManagedGridNode> nodes = new EnumMap<>(Direction.class);

    MeEndpoints(ConnectorBlockEntity connector) {
        this.connector = connector;
    }

    /** Creates nodes on sides that now face an AE2 grid host, and destroys those that no longer do. */
    void refresh() {
        if (!(connector.getLevel() instanceof ServerLevel level) || connector.isRemoved()) {
            return;
        }
        BlockPos pos = connector.getBlockPos();
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            // A cable or machine offers a node on the side facing us; a cable bus holding only a part (a storage
            // bus, say) doesn't, and AE2 wouldn't connect to it anyway.
            boolean wanted = connector.isDeviceEnabled(side) && level.isLoaded(next)
                    && level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, next, null) != null
                    && GridHelper.getExposedNode(level, next, side.getOpposite()) != null;
            IManagedGridNode existing = nodes.get(side);
            if (wanted && existing == null) {
                IManagedGridNode node = GridHelper.createManagedNode(this, LISTENER)
                        .setFlags(GridFlags.DENSE_CAPACITY)
                        .setExposedOnSides(EnumSet.of(side))
                        .setIdlePowerUsage(ReticulumConfig.ME_ENDPOINT_POWER.getAsDouble())
                        .setInWorldNode(true)
                        .setVisualRepresentation(ModItems.CONNECTOR.get())
                        .setTagName("side_" + side.getSerializedName());
                nodes.put(side, node);
                node.create(level, pos);
            } else if (!wanted && existing != null) {
                existing.destroy();
                nodes.remove(side);
            }
        }
    }

    void destroy() {
        nodes.values().forEach(IManagedGridNode::destroy);
        nodes.clear();
    }

    /** Sides with an ME endpoint. */
    public Set<Direction> sides() {
        return nodes.isEmpty() ? Set.of() : EnumSet.copyOf(nodes.keySet());
    }

    /**
     * How full the way to the ME controller is: of the cable on this side and every node on AE2's own route from
     * this endpoint to the controller, the one with the least room left (usually a normal cable carrying other
     * machines' channels too), and how many channels go through this endpoint.
     */
    @Nullable
    MeLinker.Usage usage(Direction side) {
        IGridNode node = node(side);
        if (node == null) {
            return null;
        }
        if (node.getInWorldConnections().get(side) == null && node.getConnections().isEmpty()) {
            return MeLinker.Usage.NOT_CONNECTED;
        }
        // The cable on this side limits the link too, even when it lies further out than us rather than towards the
        // controller. Not what has no channel limit of its own, though: the controller itself reports none (0), and
        // an endpoint right on it is limited only by its own dense capacity.
        appeng.api.networking.IGridConnection cable = node.getInWorldConnections().get(side);
        IGridNode neighbor = cable == null ? null : cable.getOtherSide(node);
        IGridNode tightest = neighbor != null && neighbor.getMaxChannels() > 0 ? neighbor : null;
        IGridNode current = node;
        for (int steps = 0; current != null && steps < 4096; steps++) {
            IGridNode parent = null;
            for (appeng.api.networking.IGridConnection connection : current.getConnections()) {
                IGridNode other = connection.getOtherSide(current);
                if (connection instanceof appeng.me.GridConnection gc && gc.getControllerRoute() == other) {
                    parent = other;
                    break;
                }
            }
            if (parent == null) {
                // current is the controller (or the route ends): it isn't a cable with a limit to report.
                break;
            }
            if (current.getMaxChannels() > 0 && (tightest == null || room(current) < room(tightest))) {
                tightest = current;
            }
            current = parent;
        }
        if (tightest == null) {
            tightest = node;
        }
        // AE2 counts on each node the channels routed through it to the machines further out.
        return new MeLinker.Usage(true, node.getUsedChannels(), tightest.getUsedChannels(), tightest.getMaxChannels());
    }

    @Override
    public Map<Direction, MeLinker.Usage> usages() {
        Map<Direction, MeLinker.Usage> out = new EnumMap<>(Direction.class);
        for (Direction side : nodes.keySet()) {
            MeLinker.Usage usage = usage(side);
            if (usage != null) {
                out.put(side, usage);
            }
        }
        return out;
    }

    private static int room(IGridNode node) {
        return node.getMaxChannels() - node.getUsedChannels();
    }

    @Nullable
    IGridNode node(Direction side) {
        IManagedGridNode managed = nodes.get(side);
        return managed == null ? null : managed.getNode();
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return node(dir);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return nodes.containsKey(dir) ? AECableType.DENSE_COVERED : AECableType.NONE;
    }
}
