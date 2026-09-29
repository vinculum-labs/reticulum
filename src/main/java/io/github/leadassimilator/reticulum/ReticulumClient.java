package io.github.leadassimilator.reticulum;

import io.github.leadassimilator.reticulum.client.LookHud;
import io.github.leadassimilator.reticulum.client.ToolHighlight;
import io.github.leadassimilator.reticulum.client.web.WebBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Reticulum.MODID, dist = Dist.CLIENT)
public class ReticulumClient {
    public ReticulumClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ReticulumClientConfig.SPEC);
        modEventBus.addListener(LookHud::registerLayer);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeModel::wrap);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::wrap);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::registerDecorator);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.FacadeXray::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.FacadeXray.clear());
        // The in-game editor's page belongs to the world being left.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.webview.WebView2Host.close());
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::registerLayer);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::onRenderLevel);
        // Leaving a world forgets what was lit there.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.IdentifyRenderer.accept(java.util.List.of()));
        NeoForge.EVENT_BUS.addListener(ToolHighlight::onRenderHighlight);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.ScreenHighlight::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(LookHud::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> WebBridge.onLoggedIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> WebBridge.onLoggedOut());
        NeoForge.EVENT_BUS.addListener(WebBridge::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.ClientPayloadHandler::onScroll);
    }
}
