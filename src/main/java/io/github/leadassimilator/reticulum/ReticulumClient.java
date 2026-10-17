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
        // The editor's web pages (shipped inside this mod for now), and the editor itself as their first app.
        io.github.leadassimilator.telispex.client.TelispexClient.init(modEventBus, container);
        WebBridge.init();
        modEventBus.addListener(LookHud::registerLayer);
        // The network tool's look follows its mode, with a mark for an alarm going off (see its item definition).
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.ToolItemProperties::register);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.AlarmClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.AlarmClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.AlarmClient.clear());
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeModel::wrap);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::register);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::registerDecorator);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.FacadeXray::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.FacadeXray.clear());
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::registerLayer);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::onRenderLevel);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.StatusBeaconRenderer::register);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::drawTexts);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::registerColors);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity.CLIENT_LOADED.clear());
        // Leaving a world forgets what was lit there.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.IdentifyRenderer.accept(java.util.List.of()));
        NeoForge.EVENT_BUS.addListener(ToolHighlight::onRenderHighlight);
        // A message too long for the line above the hotbar goes to chat, where it wraps.
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.LongMessages::onSystemMessage);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.ScreenHighlight::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(LookHud::onClientTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.ClientPayloadHandler::onScroll);
    }
}
