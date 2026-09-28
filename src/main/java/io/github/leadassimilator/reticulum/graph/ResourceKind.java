package io.github.leadassimilator.reticulum.graph;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** What a flow moves. Chemicals need Mekanism; without it no device offers them. */
public enum ResourceKind implements StringRepresentable {
    ITEM(false),
    FLUID(true),
    ENERGY(true),
    CHEMICAL(true);

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
