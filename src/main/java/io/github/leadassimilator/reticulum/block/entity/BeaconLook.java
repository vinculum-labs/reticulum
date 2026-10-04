package io.github.leadassimilator.reticulum.block.entity;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * How a Status Beacon looks and sounds in one state: its color (lens, beam and glow), which effects it shows, and its
 * sound — which, how high, how often (every {@code interval} ticks) and how far it carries ({@code range} blocks).
 */
public record BeaconLook(int color, int flags, ResourceLocation sound, float pitch, int interval, int range) {
    public static final int BEAM = 1;
    /** The beam goes on through solid blocks (otherwise it stops at the first). */
    public static final int BEAM_THROUGH = 2;
    public static final int GLOW = 4;
    /** The glow shows through walls and floors. */
    public static final int GLOW_THROUGH = 8;
    public static final int SOUND = 16;
    /** Every other play is lower, like a siren. */
    public static final int TWO_TONE = 32;
    public static final int ALL = BEAM | BEAM_THROUGH | GLOW | GLOW_THROUGH | SOUND | TWO_TONE;

    public static final BeaconLook WARNING = new BeaconLook(0xFFB020, BEAM | GLOW,
            ResourceLocation.withDefaultNamespace("block.note_block.chime"), 1.2f, 40, 32);
    public static final BeaconLook CRITICAL = new BeaconLook(0xFF3020, BEAM | BEAM_THROUGH | GLOW | GLOW_THROUGH | SOUND | TWO_TONE,
            ResourceLocation.withDefaultNamespace("block.note_block.pling"), 1.6f, 10, 64);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("color", color);
        tag.putInt("flags", flags);
        tag.putString("sound", sound.toString());
        tag.putFloat("pitch", pitch);
        tag.putInt("interval", interval);
        tag.putInt("range", range);
        return tag;
    }

    /** Read back, kept within the server's limits; anything missing or unknown falls back to {@code fallback}'s. */
    public static BeaconLook load(CompoundTag tag, BeaconLook fallback) {
        ResourceLocation sound = ResourceLocation.tryParse(tag.getString("sound"));
        if (sound == null || !BuiltInRegistries.SOUND_EVENT.containsKey(sound) || banned(sound)) {
            sound = fallback.sound();
        }
        return new BeaconLook(
                tag.contains("color") ? tag.getInt("color") & 0xFFFFFF : fallback.color(),
                tag.contains("flags") ? tag.getInt("flags") & ALL : fallback.flags(),
                sound,
                tag.contains("pitch") ? Math.max(0.5f, Math.min(2f, tag.getFloat("pitch"))) : fallback.pitch(),
                Math.max(minInterval(), tag.contains("interval") ? Math.min(12_000, tag.getInt("interval")) : fallback.interval()),
                Math.max(1, Math.min(maxRange(), tag.contains("range") ? tag.getInt("range") : fallback.range())));
    }

    private static int minInterval() {
        try {
            return ReticulumConfig.BEACON_MIN_INTERVAL.getAsInt();
        } catch (IllegalStateException notLoaded) {
            return 10;
        }
    }

    private static int maxRange() {
        try {
            return ReticulumConfig.BEACON_MAX_RANGE.getAsInt();
        } catch (IllegalStateException notLoaded) {
            return 256;
        }
    }

    private static boolean banned(ResourceLocation sound) {
        try {
            return ReticulumConfig.BEACON_BANNED_SOUNDS.get().contains(sound.toString());
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }
}
