package io.github.leadassimilator.reticulum;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.leadassimilator.reticulum.block.PlacementRedirect;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.command.ReticulumCommand;
import io.github.leadassimilator.reticulum.datagen.DataGenerators;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.gametest.GameTests;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.ReticulumSavedData;
import io.github.leadassimilator.reticulum.protocol.AlarmPush;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.web.WebApi;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModCreativeTabs;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import io.github.leadassimilator.reticulum.security.BreakProtection;
import io.github.leadassimilator.reticulum.topology.Topology;
import io.github.leadassimilator.telispex.Telispex;
import io.github.leadassimilator.telispex.relay.Relay;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mod(Reticulum.MODID)
public class Reticulum {
    public static final String MODID = "reticulum";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Reticulum(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModDataComponents.COMPONENTS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(Payloads::register);
        // The editor's web pages (shipped inside this mod for now): their requests reach the server through Telispex.
        Telispex.init(modEventBus);
        Relay.register(MODID, WebApi::onRequest);
        modEventBus.addListener(DataGenerators::gatherData);
        GameTests.init(modEventBus);
        if (Mods.CHEMICALS != null) {
            Mods.CHEMICALS.register(modEventBus);
        }
        modEventBus.addListener(ConnectorBlockEntity::registerCapabilities);
        modEventBus.addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(
                Capabilities.Energy.BLOCK, ModBlockEntities.CONTROLLER.get(),
                (controller, side) -> controller.energyHandler()));
        if (Mods.ME != null) {
            modEventBus.addListener(Mods.ME::registerCapabilities);
        }
        modEventBus.addListener((InterModEnqueueEvent event) -> {
            if (Mods.TOP) {
                Mods.create("integration.top.TopIntegration", Runnable.class).run();
            }
        });

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ReticulumCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(BreakProtection::onBreak);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            Topology.onChunkChanged(event);
            WebApi.onChunkChanged(event);
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            Topology.onChunkChanged(event);
            WebApi.onChunkChanged(event);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PlacementRedirect::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(Topology::onServerTick);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onServerTick);
        NeoForge.EVENT_BUS.addListener(Identifies::onServerTick);
        NeoForge.EVENT_BUS.addListener(Identifies::onLogin);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(WebApi::onServerTick);
        NeoForge.EVENT_BUS.addListener(AlarmPush::onServerTick);
        NeoForge.EVENT_BUS.addListener(AlarmPush::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(ReticulumSavedData::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(ReticulumSavedData::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(AlarmPush::onServerStopped);
        NeoForge.EVENT_BUS.addListener(WebApi::onLogout);
        NeoForge.EVENT_BUS.addListener(WebApi::onServerStopped);

        modContainer.registerConfig(ModConfig.Type.SERVER, ReticulumConfig.SPEC);
    }
}
