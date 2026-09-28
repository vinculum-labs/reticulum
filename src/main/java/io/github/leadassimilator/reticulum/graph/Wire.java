package io.github.leadassimilator.reticulum.graph;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;

/**
 * A wire from one node's output to another node's input. {@code output} and {@code input} pick the output and input
 * on nodes with several (a filter's outputs: 0 = match, 1 = reject; a device node's numbered inputs and outputs).
 * A signal wire into input {@link #GATE} switches its node on (signal above 0) or off.
 * Wires leaving the same output are ordered by {@code order}.
 */
public record Wire(UUID id, UUID from, int output, UUID to, int input, int order) {
    public static final int MATCH = 0;
    public static final int REJECT = 1;
    /** The input every node has for a signal that switches it on or off. */
    public static final int GATE = -1;

    public static final Codec<Wire> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(Wire::id),
            Ids.UUID_CODEC.fieldOf("from").forGetter(Wire::from),
            Codec.INT.optionalFieldOf("output", 0).forGetter(Wire::output),
            Ids.UUID_CODEC.fieldOf("to").forGetter(Wire::to),
            Codec.INT.optionalFieldOf("input", 0).forGetter(Wire::input),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Wire::order)
    ).apply(i, Wire::new));

    public Wire(UUID id, UUID from, int output, UUID to, int order) {
        this(id, from, output, to, 0, order);
    }

    public static Wire of(Node from, Node to) {
        return new Wire(UUID.randomUUID(), from.id(), MATCH, to.id(), 0);
    }
}
