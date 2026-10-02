package io.github.leadassimilator.reticulum.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.graph.Redstone;

/** What switches the network as a whole on and off: its button and redstone condition. Sheets are switched by their nodes. */
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

}
