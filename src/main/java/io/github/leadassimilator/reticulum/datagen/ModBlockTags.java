package io.github.leadassimilator.reticulum.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

public class ModBlockTags extends BlockTagsProvider {
    public ModBlockTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, Reticulum.MODID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(ModBlocks.CABLE.get(), ModBlocks.CONNECTOR.get(), ModBlocks.CONTROLLER.get(),
                ModBlocks.STATUS_BEACON.get());
    }
}
