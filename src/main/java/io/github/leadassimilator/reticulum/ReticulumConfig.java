package io.github.leadassimilator.reticulum;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ReticulumConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue PROTECT_PRIVATE_BLOCKS = BUILDER
            .comment("Only members may break cables, connectors and controllers of Private networks.")
            .define("protectPrivateBlocks", true);

    public static final ModConfigSpec.BooleanValue OPERATOR_BYPASS = BUILDER
            .comment("Server operators (permission level 2+) bypass network security. Every bypass is logged.")
            .define("operatorBypass", true);

    public static final ModConfigSpec.IntValue MAX_INTERACT_DISTANCE = BUILDER
            .comment("How far (in blocks) a player may be from a block to change it through its screen.")
            .defineInRange("maxInteractDistance", 8, 1, 64);

    public static final ModConfigSpec.IntValue ENERGY_PER_TICK = BUILDER
            .comment("Most FE one energy route moves per tick. Set above Mekanism's best cables by default.")
            .defineInRange("energyPerTick", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue FLUID_PER_TICK = BUILDER
            .comment("Most mB one fluid route moves per tick. Set above Mekanism's best pipes by default.")
            .defineInRange("fluidPerTick", 1_024_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue OVERFLOW_ITEM_STACKS = BUILDER
            .comment("Item stacks a network's overflow buffer holds before item flows pause.")
            .defineInRange("overflowItemStacks", 9, 1, 1024);

    public static final ModConfigSpec.IntValue OVERFLOW_FLUID_BUCKETS = BUILDER
            .comment("Buckets per fluid a network's overflow buffer holds before fluid flows pause.")
            .defineInRange("overflowFluidBuckets", 16, 1, 1_000_000);

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumConfig() {
    }
}
