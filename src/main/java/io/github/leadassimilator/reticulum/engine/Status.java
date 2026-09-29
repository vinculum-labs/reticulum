package io.github.leadassimilator.reticulum.engine;

import java.util.Locale;

/**
 * Why a node moved nothing on its last attempt. When several reasons apply within one operation, the one that got
 * furthest wins (a later constant beats an earlier one): "target full" says more than "filtered out".
 */
public enum Status {
    DEVICE_OFFLINE,
    SOURCE_EMPTY,
    KEEP_RULE,
    FILTERED_OUT,
    TARGET_OFFLINE,
    RATE_LIMITED,
    CAP_REACHED,
    TARGET_FULL,
    WAITING_FOR_PARTS,
    NO_ROOM_FOR_KIT,
    OVERFLOW_FULL,
    /** Its redstone condition or gate has it switched off (set directly, never through {@link #furthest}). */
    SWITCHED_OFF,
    /** The controller doesn't have the FE this needs (set directly, never through {@link #furthest}). */
    NO_POWER;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The more informative of two reasons; either may be null. */
    static Status furthest(Status a, Status b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
