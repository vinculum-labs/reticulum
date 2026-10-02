package io.github.leadassimilator.reticulum.network;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.util.StringRepresentable;

/**
 * What switches the network as a whole on and off: its button, redstone at the controller block, and a Network node
 * for it in another network. Sheets are switched by their nodes, nodes by signal wires into their gates.
 */
public final class Switches {
    public static final Codec<Switches> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled),
            Redstone.CODEC.optionalFieldOf("redstone", Redstone.IGNORED).forGetter(s -> s.redstone),
            Held.CODEC.optionalFieldOf("held").forGetter(s -> Optional.ofNullable(s.held))
    ).apply(i, Switches::new));

    private boolean enabled = true;
    private Redstone redstone = Redstone.IGNORED;
    @Nullable
    private Held held;

    public Switches() {
    }

    private Switches(boolean enabled, Redstone redstone, Optional<Held> held) {
        this.enabled = enabled;
        this.redstone = redstone;
        this.held = held.orElse(null);
    }

    /** The Network node in another network that switches this one, and what it last said; null when none does. */
    @Nullable
    public Held held() {
        return held;
    }

    public void setHeld(@Nullable Held held) {
        this.held = held;
    }

    /** Whether the network switching this one (if any) lets it run. */
    public boolean heldOn() {
        return held == null || held.on();
    }

    /** Switched by the Network node {@code node} in network {@code network}: on as it last said. */
    public record Held(UUID network, UUID node, boolean on) {
        public static final Codec<Held> CODEC = RecordCodecBuilder.create(i -> i.group(
                io.github.leadassimilator.reticulum.network.Ids.UUID_CODEC.fieldOf("network").forGetter(Held::network),
                io.github.leadassimilator.reticulum.network.Ids.UUID_CODEC.fieldOf("node").forGetter(Held::node),
                Codec.BOOL.optionalFieldOf("on", true).forGetter(Held::on)
        ).apply(i, Held::new));
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
