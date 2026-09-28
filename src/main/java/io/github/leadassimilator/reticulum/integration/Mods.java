package io.github.leadassimilator.reticulum.integration;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.integration.mekanism.MekanismChemicals;
import net.minecraft.core.Direction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Which optional mods are present. Integration classes are touched only after checking here. */
public final class Mods {
    public static final boolean MEKANISM = ModList.get().isLoaded("mekanism");
    public static final boolean AE2 = ModList.get().isLoaded("ae2");

    private Mods() {
    }

    /** Mekanism's chemical capability, or null without Mekanism. */
    @Nullable
    public static BlockCapability<?, Direction> chemicalCapability() {
        return MEKANISM ? MekanismChemicals.CAPABILITY : null;
    }
}
