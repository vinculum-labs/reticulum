package io.github.leadassimilator.reticulum;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ReticulumConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("security");
    }

    public static final ModConfigSpec.BooleanValue PROTECT_PRIVATE_BLOCKS = BUILDER
            .comment("Only members may break cables, connectors and controllers of Private networks.")
            .define("protectPrivateBlocks", true);

    public static final ModConfigSpec.BooleanValue OPERATOR_BYPASS = BUILDER
            .comment("Server operators (permission level 2+) bypass network security. Every bypass is logged.")
            .define("operatorBypass", true);

    public static final ModConfigSpec.IntValue MAX_INTERACT_DISTANCE = BUILDER
            .comment("How far (in blocks) a player may be from a block to change it through its screen.")
            .defineInRange("maxInteractDistance", 8, 1, 64);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("wireless");
    }

    public static final ModConfigSpec.IntValue ANTENNA_RANGE = BUILDER
            .comment("How far (in blocks) from the controller a tier 1 antenna reaches wireless connectors, in the same dimension.",
                    "Tier 2 reaches the whole dimension, tier 3 every dimension.")
            .defineInRange("antennaRange", 128, 1, 30_000_000);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("power");
    }

    public static final ModConfigSpec.EnumValue<io.github.leadassimilator.reticulum.engine.Power.Preset> POWER_PRESET = BUILDER
            .comment("What networks cost to run. FREE (the default): nothing. BALANCED: 10 FE/t per network, 2 FE/t per connector,",
                    "8 / 32 FE/t per wireless connector (tier 1 / tier 2+ antenna) plus 0.05 FE/t per block of distance or 256 FE/t",
                    "in another dimension, and 1 FE per item operation. CUSTOM: the cost values below.",
                    "The controller holds the FE; it takes it from anything that pushes into it, and pulls from FE sources next to it.")
            .defineEnum("powerPreset", io.github.leadassimilator.reticulum.engine.Power.Preset.FREE);

    public static final ModConfigSpec.IntValue CONTROLLER_CAPACITY = BUILDER
            .comment("FE a controller holds.")
            .defineInRange("controllerCapacity", 1_000_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue COST_BASE = cost("costBase", "CUSTOM: FE per tick for every running network.", 10);
    public static final ModConfigSpec.DoubleValue COST_PER_CONNECTOR = cost("costPerConnector", "CUSTOM: FE per tick for each connector.", 2);
    public static final ModConfigSpec.DoubleValue COST_WIRELESS_TIER_1 = cost("costWirelessTier1",
            "CUSTOM: FE per tick for each wireless connector on a tier 1 antenna.", 8);
    public static final ModConfigSpec.DoubleValue COST_WIRELESS_TIER_2 = cost("costWirelessTier2",
            "CUSTOM: FE per tick for each wireless connector on a tier 2 or 3 antenna.", 32);
    public static final ModConfigSpec.DoubleValue COST_WIRELESS_PER_BLOCK = cost("costWirelessPerBlock",
            "CUSTOM: FE per tick per block between a wireless connector and the controller.", 0.05);
    public static final ModConfigSpec.DoubleValue COST_CROSS_DIMENSION = cost("costCrossDimension",
            "CUSTOM: FE per tick for each wireless connector in another dimension than the controller.", 256);
    public static final ModConfigSpec.DoubleValue COST_PER_OPERATION = cost("costPerOperation",
            "CUSTOM: FE for each item operation that moves something. Energy, fluid and chemical flows never pay it.", 1);

    private static ModConfigSpec.DoubleValue cost(String key, String comment, double balanced) {
        return BUILDER.comment(comment).defineInRange(key, balanced, 0, 1_000_000);
    }

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("throughput");
    }

    public static final ModConfigSpec.IntValue ENERGY_PER_TICK = BUILDER
            .comment("Most FE one energy route moves per tick. Set above Mekanism's best cables by default.")
            .defineInRange("energyPerTick", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue FLUID_PER_TICK = BUILDER
            .comment("Most mB one fluid route moves per tick. Set above Mekanism's best pipes by default.")
            .defineInRange("fluidPerTick", 1_024_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.LongValue CHEMICAL_PER_TICK = BUILDER
            .comment("Most mB one chemical route moves per tick. Set above Mekanism's best tubes by default.")
            .defineInRange("chemicalPerTick", 1_024_000L, 1L, Long.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue SLOT_RANGES_THROUGH_FACES = BUILDER
            .comment("A slot range on a block with its faces left to work out moves through the block's own slots. Some blocks",
                    "(Mekanism machines) take and give items only through their faces, so nothing moves there unless faces are picked.",
                    "On, such blocks are detected (a few simulated moves per operation) and their slot ranges move through the faces",
                    "instead, each face numbering its own slots. Off (the default), nothing is probed.")
            .define("slotRangesThroughFaces", false);

    public static final ModConfigSpec.IntValue OVERFLOW_ITEM_STACKS = BUILDER
            .comment("Item stacks a network's overflow buffer holds before item flows pause.")
            .defineInRange("overflowItemStacks", 9, 1, 1024);

    public static final ModConfigSpec.IntValue OVERFLOW_FLUID_BUCKETS = BUILDER
            .comment("Buckets per fluid a network's overflow buffer holds before fluid flows pause.")
            .defineInRange("overflowFluidBuckets", 16, 1, 1_000_000);

    public static final ModConfigSpec.IntValue OVERFLOW_CHEMICAL_BUCKETS = BUILDER
            .comment("Buckets per chemical a network's overflow buffer holds before chemical flows pause.")
            .defineInRange("overflowChemicalBuckets", 16, 1, 1_000_000);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("identify");
    }

    public static final ModConfigSpec.IntValue IDENTIFY_AUTO_OFF_MINUTES = BUILDER
            .comment("Minutes after which an identify turns itself off. 0 (the default) keeps it on until someone turns it off.")
            .defineInRange("identifyAutoOffMinutes", 0, 0, 100_000);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("ae2");
    }

    public static final ModConfigSpec.DoubleValue ME_ENDPOINT_POWER = BUILDER
            .comment("AE power (AE/t) each ME endpoint (a connector side facing AE2) draws from its AE2 network.")
            .defineInRange("meEndpointPower", 1.0, 0.0, 1000.0);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.push("beacons");
    }

    public static final ModConfigSpec.IntValue BEACON_MAX_RANGE = BUILDER
            .comment("How far (in blocks) a Status Beacon's sound may be set to carry. Players only hear beacons in chunks they have loaded.")
            .defineInRange("beaconMaxRange", 256, 1, 1024);

    public static final ModConfigSpec.IntValue BEACON_MIN_INTERVAL = BUILDER
            .comment("The shortest a Status Beacon may wait between plays of its sound, in ticks (20 a second).")
            .defineInRange("beaconMinInterval", 10, 1, 1200);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> BEACON_BANNED_SOUNDS = BUILDER
            .comment("Sounds Status Beacons may not play, by id (\"minecraft:entity.ghast.scream\", say).")
            .defineListAllowEmpty("beaconBannedSounds", java.util.List.of(), () -> "", o -> o instanceof String);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumConfig() {
    }
}
