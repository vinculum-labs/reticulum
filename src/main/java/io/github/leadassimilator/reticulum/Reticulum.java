package io.github.leadassimilator.reticulum;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.leadassimilator.reticulum.command.ReticulumCommand;
import io.github.leadassimilator.reticulum.datagen.DataGenerators;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModCreativeTabs;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import io.github.leadassimilator.reticulum.registry.ModItems;
import io.github.leadassimilator.reticulum.security.BreakProtection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
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

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ReticulumCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(BreakProtection::onBreak);

        modContainer.registerConfig(ModConfig.Type.SERVER, ReticulumConfig.SPEC);
    }
}
