package io.github.leadassimilator.telispex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.leadassimilator.telispex.relay.Relay;
import net.neoforged.bus.api.IEventBus;

/**
 * Web pages for mods: a local web server in the game client that serves each app's page, a WebSocket to it, a relay
 * that carries its requests to the server (where the app's handler answers them), and a view that shows it inside the
 * game window. Each app gets its own origin, {@code <app>.localhost}, where the browser can reach one, else its own path.
 *
 * <p>Call {@link #init} from the mod's constructor (both sides) and
 * {@link io.github.leadassimilator.telispex.client.TelispexClient#init} from its client constructor.
 */
public final class Telispex {
    public static final String ID = "telispex";
    public static final Logger LOGGER = LoggerFactory.getLogger("Telispex");

    private Telispex() {
    }

    public static void init(IEventBus modEventBus) {
        Relay.init(modEventBus);
    }

    /**
     * App ids are written as mod ids are (lowercase letters, digits and _, starting with a letter, at most 63
     * characters), as they name a path and a resource namespace too. Usually the mod's own id.
     */
    public static void checkAppId(String app) {
        if (!app.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Not a valid app id: " + app);
        }
    }

    /** The app's host name label: its id with - for _ (host names don't take _), so each label names one app. */
    public static String hostLabel(String app) {
        return app.replace('_', '-');
    }
}
