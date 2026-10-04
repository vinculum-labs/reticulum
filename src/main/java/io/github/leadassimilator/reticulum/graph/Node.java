package io.github.leadassimilator.reticulum.graph;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.Ids;
import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.util.StringRepresentable;

/**
 * A node on a sheet. {@code x}/{@code y} are its position in the editor and have no effect on the engine.
 * {@code gate} is how a signal wired into its gate switches it. {@code note} says what it is for (shown in the editor and on the
 * dashboard), and has no effect either.
 */
public record Node(UUID id, NodeSettings settings, Optional<String> label, boolean enabled, float x, float y, Gate gate, Optional<String> note) {
    public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(Node::id),
            NodeSettings.CODEC.fieldOf("settings").forGetter(Node::settings),
            Codec.STRING.optionalFieldOf("label").forGetter(Node::label),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Node::enabled),
            Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(Node::x),
            Codec.FLOAT.optionalFieldOf("y", 0f).forGetter(Node::y),
            Gate.CODEC.optionalFieldOf("gate", Gate.WHILE_ON).forGetter(Node::gate),
            Codec.STRING.optionalFieldOf("note").forGetter(Node::note)
    ).apply(i, Node::new));

    public static Node of(NodeSettings settings) {
        return new Node(UUID.randomUUID(), settings, Optional.empty(), true, 0, 0, Gate.WHILE_ON, Optional.empty());
    }

    public Node withId(UUID id) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public Node with(NodeSettings settings) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public Node withLabel(Optional<String> label) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public Node withEnabled(boolean enabled) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public Node withGate(Gate gate) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public Node at(float x, float y) {
        return new Node(id, settings, label, enabled, x, y, gate, note);
    }

    public NodeSettings.Kind kind() {
        return settings.kind();
    }

    /** How a signal into a node's gate switches it. */
    public enum Gate implements LowercaseName {
        /** On while the signal is above 0. */
        WHILE_ON,
        /**
         * Off, except for one operation each time the signal rises above 0: item flows move once, continuous flows run
         * for one tick. A one-tick pulse into a While on gate usually misses the tick an item flow works on.
         */
        PULSE;

        public static final Codec<Gate> CODEC = StringRepresentable.fromEnum(Gate::values);
    }
}
