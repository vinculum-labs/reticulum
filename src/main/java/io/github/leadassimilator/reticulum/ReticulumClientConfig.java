package io.github.leadassimilator.reticulum;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings for the game client. */
public final class ReticulumClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("identify");
    }

    public static final ModConfigSpec.BooleanValue IDENTIFY_THROUGH_WALLS = BUILDER
            .comment("Draw identified blocks through walls, so they can be found behind other blocks.")
            .define("identifyThroughWalls", true);

    public static final ModConfigSpec.IntValue IDENTIFY_BEAM_DISTANCE = BUILDER
            .comment("Beyond this many blocks, an identified block also gets a beam up to the sky, to find it from far away.")
            .defineInRange("identifyBeamDistance", 32, 0, 100_000);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("alarms");
    }

    // What the network tool does about alarms going off on your networks, per severity (also set from the tool:
    // right-click the air in Status mode).
    public static final ModConfigSpec.BooleanValue TOOL_COLOR_WARNING = BUILDER
            .comment("The network tool shows a yellow mark while a warning is going off.")
            .define("toolColorWarning", true);
    public static final ModConfigSpec.BooleanValue TOOL_COLOR_CRITICAL = BUILDER
            .comment("The network tool shows a red mark while a critical alarm is going off.")
            .define("toolColorCritical", true);
    public static final ModConfigSpec.BooleanValue TOOL_SOUND_WARNING = BUILDER
            .comment("A chime now and then while a warning is going off and the network tool is in your hotbar.")
            .define("toolSoundWarning", false);
    public static final ModConfigSpec.BooleanValue TOOL_SOUND_CRITICAL = BUILDER
            .comment("A siren while a critical alarm is going off and the network tool is in your hotbar.")
            .define("toolSoundCritical", true);
    public static final ModConfigSpec.BooleanValue MARKERS_WARNING = BUILDER
            .comment("In Status mode, the network tool marks blocks with a warning (offline devices, what warnings read).")
            .define("markersWarning", true);
    public static final ModConfigSpec.IntValue TOOL_SOUND_EVERY_WARNING = BUILDER
            .comment("Seconds between the network tool's chimes while a warning is going off (a two-tone siren takes 2, so at least that).")
            .defineInRange("toolSoundEveryWarning", 10, 1, 3600);
    public static final ModConfigSpec.IntValue TOOL_SOUND_EVERY_CRITICAL = BUILDER
            .comment("Seconds between the network tool's sirens while a critical alarm is going off (a two-tone siren takes 2, so at least that).")
            .defineInRange("toolSoundEveryCritical", 10, 1, 3600);
    public static final ModConfigSpec.ConfigValue<String> TOOL_SOUND_ID_WARNING = BUILDER
            .comment("The sound the network tool plays for a warning, by id.")
            .define("toolSoundIdWarning", "minecraft:block.note_block.chime");
    public static final ModConfigSpec.DoubleValue TOOL_SOUND_PITCH_WARNING = BUILDER
            .comment("Its pitch (0.5 to 2).")
            .defineInRange("toolSoundPitchWarning", 1.2, 0.5, 2.0);
    public static final ModConfigSpec.DoubleValue TOOL_SOUND_PITCH2_WARNING = BUILDER
            .comment("The second tone's pitch, when two-tone (0.5 to 2).")
            .defineInRange("toolSoundPitch2Warning", 0.9, 0.5, 2.0);
    public static final ModConfigSpec.BooleanValue TOOL_SOUND_TWO_TONE_WARNING = BUILDER
            .comment("Play it as a short two-tone siren (four notes) instead of once.")
            .define("toolSoundTwoToneWarning", false);
    public static final ModConfigSpec.ConfigValue<String> TOOL_SOUND_ID_CRITICAL = BUILDER
            .comment("The sound the network tool plays for a critical alarm, by id.")
            .define("toolSoundIdCritical", "minecraft:block.note_block.pling");
    public static final ModConfigSpec.DoubleValue TOOL_SOUND_PITCH_CRITICAL = BUILDER
            .comment("Its pitch (0.5 to 2).")
            .defineInRange("toolSoundPitchCritical", 1.6, 0.5, 2.0);
    public static final ModConfigSpec.DoubleValue TOOL_SOUND_PITCH2_CRITICAL = BUILDER
            .comment("The second tone's pitch, when two-tone (0.5 to 2).")
            .defineInRange("toolSoundPitch2Critical", 1.2, 0.5, 2.0);
    public static final ModConfigSpec.BooleanValue TOOL_SOUND_TWO_TONE_CRITICAL = BUILDER
            .comment("Play it as a short two-tone siren (four notes) instead of once.")
            .define("toolSoundTwoToneCritical", true);
    public static final ModConfigSpec.BooleanValue MUTE_REDSTONE_BEACONS = BUILDER
            .comment("Mute Status Beacons in Redstone mode (anyone can set those off). Beacons showing a network's alarms still sound.")
            .define("muteRedstoneBeacons", false);
    public static final ModConfigSpec.BooleanValue HIDE_REDSTONE_BEACON_GLOW = BUILDER
            .comment("Don't draw Redstone-mode Status Beacons' glow through walls (it still shows where you can see the beacon).")
            .define("hideRedstoneBeaconGlow", false);
    public static final ModConfigSpec.BooleanValue MARKERS_CRITICAL = BUILDER
            .comment("In Status mode, the network tool marks what critical alarms read.")
            .define("markersCritical", true);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumClientConfig() {
    }
}
