package io.github.leadassimilator.reticulum.graph;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * A wire from one node's output to another node's input. {@code output} picks the output on nodes with several
 * (a filter's 0 = match, 1 = reject). Wires leaving the same output are ordered by {@code order}.
 */
public record Wire(UUID id, UUID from, int output, UUID to, int order) {
    public static final int MATCH = 0;
    public static final int REJECT = 1;

    public static final Codec<Wire> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Wire::id),
            UUIDUtil.CODEC.fieldOf("from").forGetter(Wire::from),
            Codec.INT.optionalFieldOf("output", 0).forGetter(Wire::output),
            UUIDUtil.CODEC.fieldOf("to").forGetter(Wire::to),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Wire::order)
    ).apply(i, Wire::new));

    public static Wire of(Node from, Node to) {
        return new Wire(UUID.randomUUID(), from.id(), MATCH, to.id(), 0);
    }
}
