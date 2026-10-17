package io.github.leadassimilator.telispex.client;

import io.github.leadassimilator.telispex.TelispexConfig;
import io.github.leadassimilator.telispex.client.view.EmbeddedView;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** The game client's side: the web server, the in-game view and their settings. */
public final class TelispexClient {
    private TelispexClient() {
    }

    /** From the mod's client constructor; {@code container} holds the settings (telispex-client.toml). */
    public static void init(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, TelispexConfig.SPEC, "telispex-client.toml");
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> Icons.clear()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> WebServer.onLoggedIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> WebServer.onLoggedOut());
        // The in-game view's page belongs to the world being left.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> EmbeddedView.close());
        // And the view itself ends with the game, before the process exits around it.
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> EmbeddedView.shutdown());
        NeoForge.EVENT_BUS.addListener(WebServer::onScreenOpening);
    }
}
