package io.github.leadassimilator.reticulum.network;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** One face of a device: the six sides plus unsided (null side) access. */
public enum FaceKey implements StringRepresentable {
    DOWN(Direction.DOWN),
    UP(Direction.UP),
    NORTH(Direction.NORTH),
    SOUTH(Direction.SOUTH),
    WEST(Direction.WEST),
    EAST(Direction.EAST),
    UNSIDED(null);

    public static final Codec<FaceKey> CODEC = StringRepresentable.fromEnum(FaceKey::values);
    public static final StreamCodec<ByteBuf, FaceKey> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], FaceKey::ordinal);

    @Nullable
    private final Direction direction;

    FaceKey(@Nullable Direction direction) {
        this.direction = direction;
    }

    public static FaceKey of(Direction direction) {
        return values()[direction.ordinal()];
    }

    @Nullable
    public Direction direction() {
        return direction;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
