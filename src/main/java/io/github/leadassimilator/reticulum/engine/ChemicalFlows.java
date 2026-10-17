package io.github.leadassimilator.reticulum.engine;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.integration.Mods;
import net.minecraft.server.level.ServerLevel;

/** Chemical flows, when Mekanism is supported (see ChemicalMover, left out of builds without it). */
interface ChemicalFlows {
    @Nullable
    ChemicalFlows INSTANCE = Mods.MEKANISM ? Mods.create("engine.ChemicalMover", ChemicalFlows.class) : null;

    void run(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source);

    /** Tries to deliver buffered chemicals along one route. */
    boolean retry(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Route route, ServerLevel level);

    /** mB of matching chemicals a port holds, for level sensors. */
    long count(NetworkRuntime.Context ctx, NodeSettings.Port port, Filters.ChemicalFilter filter, Filters.Context context);
}
