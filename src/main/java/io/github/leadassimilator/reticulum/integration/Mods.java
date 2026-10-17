package io.github.leadassimilator.reticulum.integration;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.core.Direction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;

/**
 * Which optional mods are present and supported. Each integration lives in its own package here and the rest of the
 * mod only reaches it through these flags and the interfaces below, so a build for a Minecraft version a mod doesn't
 * exist for can leave its package out (see build.gradle): the mod then counts as absent, even when installed.
 */
public final class Mods {
    private static final String PACKAGE = "io.github.leadassimilator.reticulum.";

    public static final boolean MEKANISM = supported("mekanism", "integration.mekanism.MekanismSupport");
    public static final boolean AE2 = supported("ae2", "integration.ae2.Ae2Support");
    public static final boolean JEI = supported("jei", "integration.jei.ReticulumJeiPlugin");
    public static final boolean JADE = supported("jade", "integration.jade.ReticulumJadePlugin");
    public static final boolean TOP = supported("theoneprobe", "integration.top.TopIntegration");

    /** Mekanism's chemicals, when supported. */
    @Nullable
    public static final ChemicalSupport CHEMICALS = MEKANISM ? create("integration.mekanism.MekanismSupport", ChemicalSupport.class) : null;
    /** AE2's ME networks, when supported. */
    @Nullable
    public static final MeSupport ME = AE2 ? create("integration.ae2.Ae2Support", MeSupport.class) : null;

    private Mods() {
    }

    /** Mekanism's chemical capability, or null without Mekanism. */
    @Nullable
    public static BlockCapability<?, Direction> chemicalCapability() {
        return CHEMICALS == null ? null : CHEMICALS.capability();
    }

    /** True when the mod is installed and this build includes its integration (the class is only looked up, not loaded). */
    private static boolean supported(String modId, String integration) {
        if (!ModList.get().isLoaded(modId)) {
            return false;
        }
        try {
            Class.forName(PACKAGE + integration, false, Mods.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            Reticulum.LOGGER.info("{} is installed, but this build of Reticulum doesn't support it", modId);
            return false;
        }
    }

    /** A new instance of an integration class (package-private ones too), by its name under the mod's package. */
    public static <T> T create(String integration, Class<T> type) {
        try {
            var constructor = Class.forName(PACKAGE + integration).asSubclass(type).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't set up " + integration, e);
        }
    }
}
