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

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumConfig() {
    }
}
