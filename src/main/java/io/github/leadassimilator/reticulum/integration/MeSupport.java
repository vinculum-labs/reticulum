package io.github.leadassimilator.reticulum.integration;

import java.util.Set;

import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.engine.MeLinker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** AE2's ME networks, as the rest of the mod sees them (see {@link Mods#ME}). */
public interface MeSupport {
    void registerCapabilities(RegisterCapabilitiesEvent event);

    /** A connector finished loading: set up its ME endpoints once AE2 is ready for it. */
    void onLoad(ConnectorBlockEntity connector);

    /** A neighbor changed or a side was attached or detached. */
    void refresh(ConnectorBlockEntity connector);

    void destroy(ConnectorBlockEntity connector);

    /** Sides of the connector at a position that have an ME endpoint (for the editor). */
    Set<Direction> sides(Level level, BlockPos pos);

    /** An AE2 block that exposes no grid node on that side (a cable bus holding only a part there, or nothing at all). */
    boolean offersNothing(Level level, BlockPos pos, Direction facing);

    /** Links ME ports across a network, one per network runtime. */
    MeLinker linker();
}
