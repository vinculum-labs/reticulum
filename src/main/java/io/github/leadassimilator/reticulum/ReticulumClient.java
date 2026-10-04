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
        // The network tool's look follows its mode (see the item model's overrides).
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> event.enqueueWork(() ->
                net.minecraft.client.renderer.item.ItemProperties.register(io.github.leadassimilator.reticulum.registry.ModItems.NETWORK_TOOL.get(),
                        io.github.leadassimilator.reticulum.item.NetworkToolItem.MODE_PROPERTY,
                        (stack, level, entity, seed) -> io.github.leadassimilator.reticulum.item.NetworkToolItem.mode(stack).ordinal() / 3f)));
        // ...and a mark for an alarm going off on its network (or any of yours).
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> event.enqueueWork(() ->
                net.minecraft.client.renderer.item.ItemProperties.register(io.github.leadassimilator.reticulum.registry.ModItems.NETWORK_TOOL.get(),
                        io.github.leadassimilator.reticulum.item.NetworkToolItem.ALARM_PROPERTY,
                        (stack, level, entity, seed) -> io.github.leadassimilator.reticulum.client.AlarmClient.toolProperty(stack))));
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.AlarmClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.AlarmClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.AlarmClient.clear());
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeModel::wrap);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::wrap);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.FacadeItemModel::registerDecorator);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.FacadeXray::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.FacadeXray.clear());
        // The in-game editor's page belongs to the world being left.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> io.github.leadassimilator.reticulum.client.webview.EmbeddedView.close());
        // And the web view itself ends with the game, before the process exits around it.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent event) -> io.github.leadassimilator.reticulum.client.webview.EmbeddedView.shutdown());
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::registerLayer);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.IdentifyRenderer::onRenderLevel);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.StatusBeaconRenderer::register);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::onRenderLevel);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::registerColors);
        modEventBus.addListener(io.github.leadassimilator.reticulum.client.BeaconEffects::registerItemColors);
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
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> WebBridge.onLoggedIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> WebBridge.onLoggedOut());
        NeoForge.EVENT_BUS.addListener(WebBridge::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.client.ClientPayloadHandler::onScroll);
    }
}
