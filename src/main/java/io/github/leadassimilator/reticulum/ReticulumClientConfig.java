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
            .comment("Port the web editor listens on. 0 (the default) tries 38291, or the port it last used — when that is taken it",
                    "picks a free one and remembers it (in reticulum-web.json next to this file). Any other value uses that port,",
                    "falling back to a free one for that run if it is taken.")
            .defineInRange("webPort", 0, 0, 65535);

    public static final ModConfigSpec.BooleanValue REMEMBER_LOGIN = BUILDER
            .comment("Keep the web editor logged in across game and browser restarts (for 90 days), so bookmarks and open pages",
                    "keep working. The secret is kept in reticulum-web.json next to this file. Set to false for a new secret each",
                    "time the game starts and a login that ends when the browser closes — you then open the editor from the game",
                    "each session. Takes effect the next time the game starts.")
            .define("rememberLogin", true);

    public static final ModConfigSpec.BooleanValue EDITOR_IN_GAME = BUILDER
            .comment("Open the editor inside the game window, with the system's own web view (Edge's WebView2 on Windows,",
                    "Safari's WKWebView on macOS, WebKitGTK on Linux), instead of the browser. Falls back to the browser when",
                    "that isn't available (on Linux, without the libwebkit2gtk-4.1 package).")
            .define("editorInGame", true);

    /** How the in-game editor shows on Windows. */
    public enum EditorDisplay {
        /** Drawn into the game's frame when this computer can, else a window. */
        AUTO,
        /** Drawn into the game's frame (a window when that fails). */
        FRAME,
        /** A window over the game: doesn't show in exclusive fullscreen. */
        WINDOW
    }

    public static final ModConfigSpec.EnumValue<EditorDisplay> EDITOR_DISPLAY = BUILDER
            .comment("On Windows, how the in-game editor shows. FRAME draws it into the game's own frame, so it shows in",
                    "fullscreen too. WINDOW puts a window over the game, which fullscreen hides (use windowed mode or the",
                    "browser there). AUTO draws into the frame when the graphics driver allows it, else uses the window.",
                    "Takes effect the next time the editor opens after leaving the world, or after a restart.")
            .defineEnum("editorDisplay", EditorDisplay.AUTO);

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
