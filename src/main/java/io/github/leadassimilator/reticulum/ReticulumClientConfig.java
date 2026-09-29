package io.github.leadassimilator.reticulum;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings for the game client. */
public final class ReticulumClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("webEditor");
    }

    public static final ModConfigSpec.ConfigValue<String> WEB_ADDRESS = BUILDER
            .comment("Address the web editor listens on. The default, 127.0.0.1, only accepts this computer.",
                    "WARNING: anything else (a LAN address, or 0.0.0.0 / :: for every interface) lets other machines reach",
                    "the editor. It is still protected by the one-time link and a session cookie, but the traffic is plain",
                    "HTTP, so anyone on the same network can read it and reuse your session. Only change this on a network you trust.",
                    "Takes effect the next time you open the editor.")
            .define("webAddress", "127.0.0.1");

    public static final ModConfigSpec.IntValue WEB_PORT = BUILDER
            .comment("Port the web editor listens on. 0 (the default) tries 38291, or the port it last used; when that is taken it",
                    "picks a free one and remembers it (in reticulum-web.json next to this file). Any other value uses that port,",
                    "falling back to a free one for that run if it is taken.")
            .defineInRange("webPort", 0, 0, 65535);

    public static final ModConfigSpec.BooleanValue REMEMBER_LOGIN = BUILDER
            .comment("Keep the web editor logged in across game and browser restarts (for 90 days), so bookmarks and open pages",
                    "keep working. The secret is kept in reticulum-web.json next to this file. Set to false for a new secret each",
                    "time the game starts and a login that ends when the browser closes; you then open the editor from the game",
                    "each session. Takes effect the next time the game starts.")
            .define("rememberLogin", true);

    public static final ModConfigSpec.BooleanValue KEEP_RUNNING_WHILE_EDITING = BUILDER
            .comment("While the web editor is open in a browser, don't pause the game when its window loses focus, so",
                    "singleplayer worlds keep running and the editor shows live throughput. Pressing Esc still pauses.")
            .define("keepRunningWhileEditing", true);

    static {
        BUILDER.pop();
    }

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

    static final ModConfigSpec SPEC = BUILDER.build();

    private ReticulumClientConfig() {
    }
}
