package io.github.leadassimilator.reticulum.graph;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * What a device pin carries. Items, fluids, energy and chemicals move in flows (chemicals need Mekanism). Redstone
 * pins carry signals: an output is the redstone the device gives out, an input the redstone its connectors give it.
 * ME pins (AE2) are the device's faces touching a connector; a wire between two joins their AE2 networks.
 */
public enum ResourceKind implements StringRepresentable {
    ITEM(false),
    FLUID(true),
    ENERGY(true),
    CHEMICAL(true),
    REDSTONE(false),
    ME(false);

    public static final Codec<ResourceKind> CODEC = StringRepresentable.fromEnum(ResourceKind::values);

    private final boolean continuous;

    ResourceKind(boolean continuous) {
        this.continuous = continuous;
    }

    /** Continuous kinds run every tick like Mekanism tubes; item flows run in operations on an interval. */
    public boolean continuous() {
        return continuous;
    }

    /** Whether flows move this kind (redstone and ME pins carry signals and links instead). */
    public boolean moves() {
        return this != REDSTONE && this != ME;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
