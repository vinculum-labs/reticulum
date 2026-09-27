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
        NeoForge.EVENT_BUS.addListener(ToolHighlight::onRenderHighlight);
        NeoForge.EVENT_BUS.addListener(LookHud::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> WebBridge.onLoggedIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> WebBridge.onLoggedOut());
    }
}
