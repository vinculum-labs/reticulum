package io.github.leadassimilator.reticulum.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.util.StringRepresentable;

/**
 * What switches the network as a whole on and off: its button, and redstone at the controller block (which another
 * network can give through a connector bound to it). Sheets are switched by their nodes, nodes by signal wires into
 * their gates.
 */
public final class Switches {
    public static final Codec<Switches> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled),
            Redstone.CODEC.optionalFieldOf("redstone", Redstone.IGNORED).forGetter(s -> s.redstone)
    ).apply(i, Switches::new));

    private boolean enabled = true;
    private Redstone redstone = Redstone.IGNORED;

    public Switches() {
    }

    private Switches(boolean enabled, Redstone redstone) {
        this.enabled = enabled;
        this.redstone = redstone;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Redstone redstone() {
        return redstone;
    }

    public void setRedstone(Redstone redstone) {
        this.redstone = redstone;
    }

    /** What redstone at the controller block does to the network. */
    public enum Redstone implements LowercaseName {
        /** Nothing: the network runs whatever the controller receives. */
        IGNORED,
        /** Runs only while the controller is powered. */
        HIGH,
        /** Stops while the controller is powered. */
        LOW;

        public static final Codec<Redstone> CODEC = StringRepresentable.fromEnum(Redstone::values);

        /** Whether the network may run with this much power at the controller. */
        public boolean allows(int power) {
            return switch (this) {
                case IGNORED -> true;
                case HIGH -> power > 0;
                case LOW -> power == 0;
            };
        }
    }
}
