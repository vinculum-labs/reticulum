package io.github.leadassimilator.reticulum;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.leadassimilator.reticulum.block.PlacementRedirect;
import io.github.leadassimilator.reticulum.command.ReticulumCommand;
import io.github.leadassimilator.reticulum.datagen.DataGenerators;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.integration.mekanism.MekanismChemicals;
import io.github.leadassimilator.reticulum.integration.top.TopIntegration;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.web.WebApi;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModCreativeTabs;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import io.github.leadassimilator.reticulum.security.BreakProtection;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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
        modEventBus.addListener(DataGenerators::gatherData);
        if (Mods.MEKANISM) {
            MekanismChemicals.init();
        }
        modEventBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, io.github.leadassimilator.reticulum.registry.ModBlockEntities.CONTROLLER.get(),
                (controller, side) -> controller.energyStorage()));
        if (Mods.AE2) {
            modEventBus.addListener(io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration::registerCapabilities);
        }
        modEventBus.addListener((InterModEnqueueEvent event) -> {
            if (io.github.leadassimilator.reticulum.integration.Mods.TOP) {
                TopIntegration.register();
            }
        });

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ReticulumCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(BreakProtection::onBreak);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> Topology.onChunkChanged(event));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> Topology.onChunkChanged(event));
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, PlacementRedirect::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(Topology::onServerTick);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onServerTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.network.Identifies::onServerTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.network.Identifies::onLogin);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkTicker::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(WebApi::onServerTick);
        NeoForge.EVENT_BUS.addListener(io.github.leadassimilator.reticulum.protocol.AlarmPush::onServerTick);
        NeoForge.EVENT_BUS.addListener(WebApi::onLogout);
        NeoForge.EVENT_BUS.addListener(WebApi::onServerStopped);

        modContainer.registerConfig(ModConfig.Type.SERVER, ReticulumConfig.SPEC);
    }
}
