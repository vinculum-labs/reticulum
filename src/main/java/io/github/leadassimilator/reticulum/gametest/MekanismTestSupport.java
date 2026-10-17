package io.github.leadassimilator.reticulum.gametest;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.integration.mekanism.MekanismChemicals;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Mekanism helpers for GameTests; only loaded when Mekanism is present. */
final class MekanismTestSupport {
    private MekanismTestSupport() {
    }

    static Block block(String path) {
        return BuiltInRegistries.BLOCK.get(Identifier.fromNamespaceAndPath("mekanism", path));
    }

    /** Puts a chemical into the block through whichever face accepts it; returns how much went in. */
    static long fill(Level level, BlockPos pos, String chemical, long amount) {
        ChemicalStack left = MekanismChemicals.stack(Identifier.fromNamespaceAndPath("mekanism", chemical), amount);
        for (Direction side : Direction.values()) {
            IChemicalHandler handler = level.getCapability(MekanismChemicals.CAPABILITY, pos, side);
            if (handler != null && left != null && !left.isEmpty()) {
                left = handler.insertChemical(left, Action.EXECUTE);
            }
        }
        return left == null ? 0 : amount - left.getAmount();
    }

    /** Mekanism sets up a machine's default side configuration when it is placed from an item, not by setBlock. */
    static void placedByPlayer(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ItemStack stack = new ItemStack(state.getBlock());
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.applyComponentsFromItemStack(stack);
            blockEntity.setChanged();
        }
        state.getBlock().setPlacedBy(level, pos, state, null, stack);
        level.invalidateCapabilities(pos);
    }

    /**
     * Whether a Mekanism cable, pipe or tube is connected (draws its arm) toward {@code side}. Only Mekanism's API is
     * compiled against, so its transmitter is read by name.
     */
    static boolean transmitterConnects(Level level, BlockPos pos, Direction side) {
        try {
            Object transmitter = level.getBlockEntity(pos).getClass().getMethod("getTransmitter").invoke(level.getBlockEntity(pos));
            byte connections = (byte) transmitter.getClass().getMethod("getAllCurrentConnections").invoke(transmitter);
            return (connections & (1 << side.ordinal())) != 0;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read the Mekanism transmitter at " + pos, e);
        }
    }

    /** Total chemical held, counted through the first face that answers. */
    static long stored(Level level, BlockPos pos) {
        IChemicalHandler handler = handler(level, pos);
        long total = 0;
        if (handler != null) {
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                total += handler.getChemicalInTank(tank).getAmount();
            }
        }
        return total;
    }

    @Nullable
    private static IChemicalHandler handler(Level level, BlockPos pos) {
        IChemicalHandler handler = level.getCapability(MekanismChemicals.CAPABILITY, pos, null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = level.getCapability(MekanismChemicals.CAPABILITY, pos, side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }
}
