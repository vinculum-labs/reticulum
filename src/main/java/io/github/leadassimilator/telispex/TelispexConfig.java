package io.github.leadassimilator.telispex;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings for the web pages every app shows: where they are served, and how they open. */
public final class TelispexConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("web");
    }

    public static final ModConfigSpec.ConfigValue<String> ADDRESS = BUILDER
            .comment("Address the web pages are served on. The default, 127.0.0.1, only accepts this computer.",
                    "WARNING: anything else (a LAN address, or 0.0.0.0 / :: for every interface) lets other machines reach",
                    "the pages. They are still protected by the one-time link and a session cookie, but the traffic is plain",
                    "HTTP, so anyone on the same network can read it and reuse your session. Only change this on a network you trust.",
                    "Takes effect the next time a page opens.")
            .define("address", "127.0.0.1");

    public static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("Port the web pages are served on. 0 (the default) tries 38291, or the port it last used — when that is taken it",
                    "picks a free one and remembers it (in telispex-web.json next to this file). Any other value uses that port,",
                    "falling back to a free one for that run if it is taken.")
            .defineInRange("port", 0, 0, 65535);

    /** How each app's pages are told apart. */
    public enum Hosts {
        /** Their own host where the browser or view can reach one, else a path. */
        AUTO,
        /** Always their own host, <app>.localhost (only when served on this computer). */
        SUBDOMAIN,
        /** Always a path on the one host, /<app>/. */
        PATH
    }

    public static final ModConfigSpec.EnumValue<Hosts> HOSTS = BUILDER
            .comment("How each mod's pages are kept apart. SUBDOMAIN gives each its own host (mymod.localhost, say), so",
                    "one mod's page can't reach another's; it needs the pages served on this computer. PATH puts them all on",
                    "one host under /<mod>/, which works from other machines and any browser. AUTO uses SUBDOMAIN where it",
                    "is known to work (served on this computer, outside macOS and Linux's in-game view), else PATH.")
            .defineEnum("hosts", Hosts.AUTO);

    public static final ModConfigSpec.BooleanValue REMEMBER_LOGIN = BUILDER
            .comment("Keep the web pages logged in across game and browser restarts (for 90 days), so bookmarks and open pages",
                    "keep working. The secrets are kept in telispex-web.json next to this file. Set to false for new secrets each",
                    "time the game starts and a login that ends when the browser closes — you then open the pages from the game",
                    "each session. Takes effect the next time the game starts.")
            .define("rememberLogin", true);

    public static final ModConfigSpec.BooleanValue IN_GAME = BUILDER
            .comment("Open pages inside the game window, with the system's own web view (Edge's WebView2 on Windows,",
                    "Safari's WKWebView on macOS, WebKitGTK on Linux), instead of the browser. Falls back to the browser when",
                    "that isn't available (on Linux, without the libwebkit2gtk-4.1 package).")
            .define("inGame", true);

    /** How the in-game view shows on Windows. */
    public enum ViewDisplay {
        /** Drawn into the game's frame when this computer can, else a window. */
        AUTO,
        /** Drawn into the game's frame (a window when that fails). */
        FRAME,
        /** A window over the game: doesn't show in exclusive fullscreen. */
        WINDOW
    }

    public static final ModConfigSpec.EnumValue<ViewDisplay> VIEW_DISPLAY = BUILDER
            .comment("On Windows, how the in-game view shows. FRAME draws it into the game's own frame, so it shows in",
                    "fullscreen too. WINDOW puts a window over the game, which fullscreen hides (use windowed mode or the",
                    "browser there). AUTO draws into the frame when the graphics driver allows it, else uses the window.",
                    "Takes effect the next time a page opens after leaving the world, or after a restart.")
            .defineEnum("viewDisplay", ViewDisplay.AUTO);

    public static final ModConfigSpec.BooleanValue KEEP_RUNNING = BUILDER
            .comment("While a page is open in a browser, don't pause the game when its window loses focus, so singleplayer",
                    "worlds keep running and the page shows them live. Pressing Esc still pauses.")
            .define("keepRunning", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TelispexConfig() {
    }
}
