package io.github.leadassimilator.reticulum.item;

import java.util.Locale;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** What the network tool does; switched with sneak + scroll. */
public enum ToolMode implements StringRepresentable {
    /** Connect or disconnect joints, attach or detach devices. */
    CONFIGURE,
    /** Copy a network (sneak + right-click a controller or connector) and bind connectors to it (right-click). */
    LINK,
    /** Light up a connector or device (right-click), or one face of a device (sneak + right-click), to find it again. */
    IDENTIFY;

    public static final Codec<ToolMode> CODEC = StringRepresentable.fromEnum(ToolMode::values);
    public static final StreamCodec<ByteBuf, ToolMode> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], ToolMode::ordinal);

    public ToolMode next(int step) {
        return values()[Math.floorMod(ordinal() + step, values().length)];
    }

    public String translationKey() {
        return "tool.reticulum.mode." + getSerializedName();
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
