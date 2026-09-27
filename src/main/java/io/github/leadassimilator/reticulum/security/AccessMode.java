package io.github.leadassimilator.reticulum.security;

import java.util.Locale;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public enum AccessMode implements StringRepresentable {
    PUBLIC,
    PASSWORD,
    PRIVATE;

    public static final Codec<AccessMode> CODEC = StringRepresentable.fromEnum(AccessMode::values);
    public static final StreamCodec<ByteBuf, AccessMode> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], AccessMode::ordinal);

    public AccessMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
