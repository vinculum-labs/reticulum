package io.github.leadassimilator.reticulum.block.entity;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * How a Status Beacon looks and sounds in one state: its color (lens, beam and glow), which effects it shows, its
 * sound — which, how high (and the second tone's, {@code pitch2}, when two-tone), how often (every {@code interval}
 * ticks) and how far it carries ({@code range} blocks) — and the text it shows above itself ({@code label}; blank in
 * Network mode: the alarms' messages) in {@code textColor} ({@link #AUTO}: a label in its color, alarms in theirs).
 */
public record BeaconLook(int color, int flags, ResourceLocation sound, float pitch, float pitch2, int interval, int range, String label,
        int textColor) {
    /** No text color of its own. */
    public static final int AUTO = -1;
    public static final int BEAM = 1;
    /** The beam goes on through solid blocks (otherwise it stops at the first). */
    public static final int BEAM_THROUGH = 2;
    public static final int GLOW = 4;
    /** The glow shows through walls and floors. */
    public static final int GLOW_THROUGH = 8;
    public static final int SOUND = 16;
    /** Every other play is lower, like a siren. */
    public static final int TWO_TONE = 32;
    /** Text above the beacon: its label, or (Network mode, no label) the alarms going off, taking turns. */
    public static final int TEXT = 64;
    /** The text shows through walls. */
    public static final int TEXT_THROUGH = 128;
    public static final int ALL = BEAM | BEAM_THROUGH | GLOW | GLOW_THROUGH | SOUND | TWO_TONE | TEXT | TEXT_THROUGH;
    public static final int LABEL_LIMIT = 64;

    public static final BeaconLook WARNING = new BeaconLook(0xFFB020, BEAM | GLOW | TEXT,
            ResourceLocation.withDefaultNamespace("block.note_block.chime"), 1.2f, 40, 32);
    public static final BeaconLook CRITICAL = new BeaconLook(0xFF3020, BEAM | BEAM_THROUGH | GLOW | GLOW_THROUGH | SOUND | TWO_TONE | TEXT,
            ResourceLocation.withDefaultNamespace("block.note_block.pling"), 1.6f, 10, 64);

    /** With the second tone a quarter lower, and no text of its own. */
    public BeaconLook(int color, int flags, ResourceLocation sound, float pitch, int interval, int range) {
        this(color, flags, sound, pitch, Math.max(0.5f, pitch * 0.75f), interval, range, "", AUTO);
    }

    public BeaconLook withColor(int color) {
        return new BeaconLook(color, flags, sound, pitch, pitch2, interval, range, label, textColor);
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("color", color);
        tag.putInt("flags", flags);
        tag.putString("sound", sound.toString());
        tag.putFloat("pitch", pitch);
        tag.putFloat("pitch2", pitch2);
        tag.putInt("interval", interval);
        tag.putInt("range", range);
        tag.putString("label", label);
        tag.putInt("text_color", textColor);
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
                pitch(tag, "pitch", fallback.pitch()),
                pitch(tag, "pitch2", fallback.pitch2()),
                Math.max(minInterval(), tag.contains("interval") ? Math.min(12_000, tag.getInt("interval")) : fallback.interval()),
                Math.max(1, Math.min(maxRange(), tag.contains("range") ? tag.getInt("range") : fallback.range())),
                tag.contains("label") ? clip(tag.getString("label")) : fallback.label(),
                tag.contains("text_color") ? (tag.getInt("text_color") < 0 ? AUTO : tag.getInt("text_color") & 0xFFFFFF) : fallback.textColor());
    }

    /** A pitch from 0.5 to 2; anything else (NaN, infinite) or missing is the fallback's. */
    private static float pitch(CompoundTag tag, String key, float fallback) {
        float value = tag.getFloat(key);
        return tag.contains(key) && Float.isFinite(value) ? Math.max(0.5f, Math.min(2f, value)) : fallback;
    }

    private static String clip(String label) {
        String trimmed = label.strip();
        return trimmed.length() > LABEL_LIMIT ? trimmed.substring(0, LABEL_LIMIT) : trimmed;
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
