package io.github.leadassimilator.reticulum;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings for the game client. */
public final class ReticulumClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> WEB_ADDRESS = BUILDER
            .comment("Address the web editor listens on. The default, 127.0.0.1, only accepts this computer.",
                    "WARNING: anything else (a LAN address, or 0.0.0.0 / :: for every interface) lets other machines reach",
                    "the editor. It is still protected by the one-time link and a session cookie, but the traffic is plain",
                    "HTTP, so anyone on the same network can read it and reuse your session. Only change this on a network you trust.",
                    "Takes effect the next time you open the editor.")
            .define("webAddress", "127.0.0.1");

    public static final ModConfigSpec.IntValue WEB_PORT = BUILDER
            .comment("Port the web editor listens on. 0 picks a free port each time; a fixed port lets you bookmark the page",
                    "(the one-time link from the game is still needed once per game session).")
            .defineInRange("webPort", 0, 0, 65535);

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumClientConfig() {
    }
}
