package io.github.leadassimilator.reticulum.graph;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;

/**
 * A node on a sheet. {@code x}/{@code y} are its position in the editor and have no effect on the engine.
 */
public record Node(UUID id, NodeSettings settings, Optional<String> label, boolean enabled, float x, float y) {
    public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(Node::id),
            NodeSettings.CODEC.fieldOf("settings").forGetter(Node::settings),
            Codec.STRING.optionalFieldOf("label").forGetter(Node::label),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Node::enabled),
            Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(Node::x),
            Codec.FLOAT.optionalFieldOf("y", 0f).forGetter(Node::y)
    ).apply(i, Node::new));

    public static Node of(NodeSettings settings) {
        return new Node(UUID.randomUUID(), settings, Optional.empty(), true, 0, 0);
    }

    public NodeSettings.Kind kind() {
        return settings.kind();
    }
}
