package io.github.leadassimilator.reticulum.graph;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** What a flow moves. Chemicals (Mekanism) and ME links join in phase 5. */
public enum ResourceKind implements StringRepresentable {
    ITEM(false),
    FLUID(true),
    ENERGY(true);

    public static final Codec<ResourceKind> CODEC = StringRepresentable.fromEnum(ResourceKind::values);

    private final boolean continuous;

    ResourceKind(boolean continuous) {
        this.continuous = continuous;
    }

    /** Continuous kinds run every tick like Mekanism tubes; item flows run in operations on an interval. */
    public boolean continuous() {
        return continuous;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
