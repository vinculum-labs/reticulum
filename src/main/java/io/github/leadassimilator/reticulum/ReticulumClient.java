package io.github.leadassimilator.reticulum;

import java.util.List;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.client.AlarmClient;
import io.github.leadassimilator.reticulum.client.BeaconEffects;
import io.github.leadassimilator.reticulum.client.ClientPayloadHandler;
import io.github.leadassimilator.reticulum.client.FacadeItemModel;
import io.github.leadassimilator.reticulum.client.FacadeModel;
import io.github.leadassimilator.reticulum.client.FacadeXray;
import io.github.leadassimilator.reticulum.client.IdentifyRenderer;
import io.github.leadassimilator.reticulum.client.LongMessages;
import io.github.leadassimilator.reticulum.client.LookHud;
import io.github.leadassimilator.reticulum.client.ScreenHighlight;
import io.github.leadassimilator.reticulum.client.StatusBeaconRenderer;
import io.github.leadassimilator.reticulum.client.ToolHighlight;
import io.github.leadassimilator.reticulum.client.ToolItemProperties;
import io.github.leadassimilator.reticulum.client.web.WebBridge;
import io.github.leadassimilator.telispex.client.TelispexClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Reticulum.MODID, dist = Dist.CLIENT)
public class ReticulumClient {
    public ReticulumClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        container.registerConfig(ModConfig.Type.CLIENT, ReticulumClientConfig.SPEC);
        // The editor's web pages (shipped inside this mod for now), and the editor itself as their first app.
        TelispexClient.init(modEventBus, container);
        WebBridge.init();
        modEventBus.addListener(LookHud::registerLayer);
        // The network tool's look follows its mode, with a mark for an alarm going off (see its item definition).
        modEventBus.addListener(ToolItemProperties::register);
        NeoForge.EVENT_BUS.addListener(AlarmClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(AlarmClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> AlarmClient.clear());
        modEventBus.addListener(FacadeModel::wrap);
        modEventBus.addListener(FacadeItemModel::register);
        modEventBus.addListener(FacadeItemModel::registerDecorator);
        NeoForge.EVENT_BUS.addListener(FacadeXray::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> FacadeXray.clear());
        modEventBus.addListener(IdentifyRenderer::registerLayer);
        modEventBus.addListener(IdentifyRenderer::registerPipelines);
        NeoForge.EVENT_BUS.addListener(IdentifyRenderer::onRenderLevel);
        modEventBus.addListener(StatusBeaconRenderer::register);
        NeoForge.EVENT_BUS.addListener(BeaconEffects::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(BeaconEffects::drawTexts);
        modEventBus.addListener(BeaconEffects::registerColors);
        NeoForge.EVENT_BUS.addListener(BeaconEffects::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                StatusBeaconBlockEntity.CLIENT_LOADED.clear());
        // Leaving a world forgets what was lit there.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> IdentifyRenderer.accept(List.of()));
        NeoForge.EVENT_BUS.addListener(ToolHighlight::onExtractOutline);
        // A message too long for the line above the hotbar goes to chat, where it wraps.
        NeoForge.EVENT_BUS.addListener(LongMessages::onSystemMessage);
        NeoForge.EVENT_BUS.addListener(ScreenHighlight::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(LookHud::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandler::onScroll);
    }
}
