package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;

import io.github.leadassimilator.reticulum.Reticulum;

import net.minecraft.core.UUIDUtil;

public final class Ids {
    /** UUIDs as strings, which is what the web UI sends and reads. */
    public static final Codec<UUID> UUID_CODEC = UUIDUtil.STRING_CODEC;

    private Ids() {
    }

    /**
     * A list that loads the elements it can and leaves out (and logs) the ones it can't, e.g. nodes of a kind that
     * no longer exists, so one stale element doesn't lose the whole sheet. Written like any list.
     */
    public static <T> Codec<List<T>> lenientList(Codec<T> element, String what) {
        return Codec.of(element.listOf(), new Decoder<>() {
            @Override
            public <O> DataResult<Pair<List<T>, O>> decode(DynamicOps<O> ops, O input) {
                return ops.getStream(input).map(stream -> {
                    List<T> out = new ArrayList<>();
                    stream.forEach(item -> element.parse(ops, item)
                            .resultOrPartial(error -> Reticulum.LOGGER.warn("Left out a {} that can't be loaded: {}", what, error))
                            .ifPresent(out::add));
                    return Pair.of(out, input);
                });
            }
        });
    }
}
