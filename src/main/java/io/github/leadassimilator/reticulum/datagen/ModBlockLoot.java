package io.github.leadassimilator.reticulum.datagen;

import java.util.List;
import java.util.Set;

import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

public class ModBlockLoot extends BlockLootSubProvider {
    public ModBlockLoot(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        dropSelf(ModBlocks.CABLE.get());
        dropSelf(ModBlocks.CONNECTOR.get());
        // The controller item keeps its network, so placing it again brings back the whole design.
        add(ModBlocks.CONTROLLER.get(), LootTable.lootTable().withPool(applyExplosionCondition(ModBlocks.CONTROLLER.get(),
                LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                        .add(LootItem.lootTableItem(ModBlocks.CONTROLLER.get())
                                .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                                        .include(ModDataComponents.NETWORK_ID.get()))))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return List.of(ModBlocks.CABLE.get(), ModBlocks.CONNECTOR.get(), ModBlocks.CONTROLLER.get());
    }
}
