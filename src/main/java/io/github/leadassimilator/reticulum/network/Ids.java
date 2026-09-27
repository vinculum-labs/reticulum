package io.github.leadassimilator.reticulum.network;

import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;

public final class Ids {
    /** UUIDs as strings, which is what the web UI sends and reads. */
    public static final Codec<UUID> UUID_CODEC = UUIDUtil.STRING_CODEC;

    private Ids() {
    }
}
