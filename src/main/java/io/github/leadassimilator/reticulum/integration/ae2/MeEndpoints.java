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
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * A connector's ME endpoints: one AE2 grid node per side that faces an AE2 cable or machine, exposed on that side
 * only, so four dense cables on four sides are four separate 32-channel ME ports. Nodes carry channels like a dense
 * cable (they need none themselves); ME link wires connect them to endpoints elsewhere.
 */
public final class MeEndpoints implements IInWorldGridNodeHost {
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
     * Channels in use on the cable (or machine) on that side, and the most it carries: AE2 limits the cable as a
     * whole, so what it already carries for other machines counts too, as does what goes through this link.
     */
    @Nullable
    String usage(Direction side) {
        IGridNode node = node(side);
        if (node == null) {
            return null;
        }
        appeng.api.networking.IGridConnection cable = node.getInWorldConnections().get(side);
        if (cable == null) {
            return "not connected";
        }
        IGridNode other = cable.getOtherSide(node);
        int max = Math.min(node.getMaxChannels(), other.getMaxChannels());
        // As AE2 draws a cable: its busiest connection carries everything behind it.
        int used = 0;
        for (appeng.api.networking.IGridConnection connection : other.getConnections()) {
            used = Math.max(used, connection.getUsedChannels());
        }
        return used + "/" + max;
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
