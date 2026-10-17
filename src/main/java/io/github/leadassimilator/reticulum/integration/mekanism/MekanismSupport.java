package io.github.leadassimilator.reticulum.integration.mekanism;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.integration.ChemicalSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** The Mekanism integration, created by {@code Mods} when Mekanism is installed. */
final class MekanismSupport implements ChemicalSupport {
    @Override
    public void register(IEventBus modBus) {
        MekanismChemicals.init();
        modBus.addListener(MekanismChemicals::registerCapabilities);
    }

    @Override
    public BlockCapability<?, Direction> capability() {
        return MekanismChemicals.CAPABILITY;
    }

    @Override
    public void collect(ServerLevel level, BlockPos pos, Map<Identifier, Long> totals) {
        MekanismChemicals.collect(level, pos, totals);
    }

    @Override
    public List<Entry> entries() {
        return MekanismCatalog.entries();
    }

    @Override
    @Nullable
    public Icon icon(Identifier id) {
        return MekanismCatalog.icon(id);
    }
}
