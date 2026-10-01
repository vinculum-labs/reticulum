package io.github.leadassimilator.reticulum.block;

import io.github.leadassimilator.reticulum.util.LowercaseName;

import net.minecraft.util.StringRepresentable;

/** What a cable or connector renders toward one side. */
public enum Link implements LowercaseName {
    NONE,
    /** Connected to a neighboring network block. */
    CABLE,
    /** Connector only: a device on this side is connected. */
    DEVICE;
}
