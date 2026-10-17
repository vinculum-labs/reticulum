package io.github.leadassimilator.reticulum.integration.ae2;

import java.util.Set;

import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.engine.MeLinker;
import io.github.leadassimilator.reticulum.integration.MeSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The AE2 integration, created by {@code Mods} when AE2 is installed. */
final class Ae2Support implements MeSupport {
    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        Ae2Integration.registerCapabilities(event);
    }

    @Override
    public void onLoad(ConnectorBlockEntity connector) {
        Ae2Integration.onLoad(connector);
    }

    @Override
    public void refresh(ConnectorBlockEntity connector) {
        Ae2Integration.refresh(connector);
    }

    @Override
    public void destroy(ConnectorBlockEntity connector) {
        Ae2Integration.destroy(connector);
    }

    @Override
    public Set<Direction> sides(Level level, BlockPos pos) {
        return Ae2Integration.sides(level, pos);
    }

    @Override
    public boolean offersNothing(Level level, BlockPos pos, Direction facing) {
        return Ae2Integration.offersNothing(level, pos, facing);
    }

    @Override
    public MeLinker linker() {
        return Ae2Links.create();
    }
}
