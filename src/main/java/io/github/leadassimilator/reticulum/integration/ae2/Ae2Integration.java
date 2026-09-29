package io.github.leadassimilator.reticulum.integration.ae2;

import java.util.Set;

import javax.annotation.Nullable;

import appeng.api.AECapabilities;
import appeng.api.networking.GridHelper;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Hooks between connectors and AE2. Only called after checking that AE2 is installed. */
public final class Ae2Integration {
    private Ae2Integration() {
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, ModBlockEntities.CONNECTOR.get(), (be, context) -> endpoints(be));
    }

    private static MeEndpoints endpoints(ConnectorBlockEntity be) {
        if (!(be.meEndpoints() instanceof MeEndpoints endpoints)) {
            MeEndpoints created = new MeEndpoints(be);
            be.setMeEndpoints(created);
            return created;
        }
        return endpoints;
    }

    /** A connector finished loading: set up its ME endpoints once AE2 is ready for it. */
    public static void onLoad(ConnectorBlockEntity be) {
        GridHelper.onFirstTick(be, loaded -> endpoints(loaded).refresh());
    }

    /** A neighbor changed or a side was attached or detached. */
    public static void refresh(ConnectorBlockEntity be) {
        endpoints(be).refresh();
    }

    public static void destroy(ConnectorBlockEntity be) {
        if (be.meEndpoints() instanceof MeEndpoints endpoints) {
            endpoints.destroy();
        }
    }

    /** Sides of the connector at a position that have an ME endpoint (for the editor). */
    public static Set<Direction> sides(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ConnectorBlockEntity be && be.meEndpoints() instanceof MeEndpoints endpoints
                ? endpoints.sides() : Set.of();
    }

    /** An AE2 block that exposes no grid node on that side (a cable bus holding only a part there, or nothing at all). */
    public static boolean offersNothing(Level level, BlockPos pos, Direction facing) {
        return level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos, null) != null
                && GridHelper.getExposedNode(level, pos, facing) == null;
    }

    @Nullable
    static MeEndpoints existing(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ConnectorBlockEntity be && be.meEndpoints() instanceof MeEndpoints endpoints
                ? endpoints : null;
    }
}
