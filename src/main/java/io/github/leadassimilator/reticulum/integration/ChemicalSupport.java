package io.github.leadassimilator.reticulum.integration;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Mekanism's chemicals, as the rest of the mod sees them (see {@link Mods#CHEMICALS}). Flows are in engine.ChemicalFlows. */
public interface ChemicalSupport {
    /** Sets up chemical filters and the connector's chemical capability. */
    void register(IEventBus modBus);

    BlockCapability<?, Direction> capability();

    /** What chemicals a block holds, by id, for the editor's filter picker (the largest amount any one side shows). */
    void collect(ServerLevel level, BlockPos pos, Map<Identifier, Long> totals);

    /** Every chemical, for the editor's filter picker. Client side. */
    List<Entry> entries();

    /** A chemical's icon, or null when there is no such chemical. Client side. */
    @Nullable
    Icon icon(Identifier id);

    record Entry(Identifier id, String name, List<String> tags) {
    }

    record Icon(Identifier texture, int tint) {
    }
}
