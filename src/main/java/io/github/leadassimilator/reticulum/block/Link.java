package io.github.leadassimilator.reticulum.block;

import java.util.Locale;

import net.minecraft.util.StringRepresentable;

/** What a cable or connector renders toward one side. */
public enum Link implements StringRepresentable {
    NONE,
    /** Connected to a neighboring network block. */
    CABLE,
    /** Connector only: a device on this side is connected. */
    DEVICE;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
