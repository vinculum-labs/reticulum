package io.github.leadassimilator.reticulum.network;

import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;

public final class Ids {
    /**
     * UUIDs as strings, which is what the web UI sends; int arrays (Minecraft's usual form, and older saves) are
     * still read.
     */
    public static final Codec<UUID> UUID_CODEC = Codec.withAlternative(UUIDUtil.STRING_CODEC, UUIDUtil.CODEC);

    private Ids() {
    }
}
