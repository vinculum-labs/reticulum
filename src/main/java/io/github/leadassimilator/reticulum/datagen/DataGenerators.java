package io.github.leadassimilator.reticulum.datagen;

import java.util.List;
import java.util.Set;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** {@code gradlew runData} writes block states, models, language, loot, recipes and tags to src/generated/resources. */
public final class DataGenerators {
    private DataGenerators() {
    }

    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        var lookup = event.getLookupProvider();
        var files = event.getExistingFileHelper();

        generator.addProvider(event.includeClient(), new ModBlockStates(output, files));
        generator.addProvider(event.includeClient(), new ModItemModels(output, files));
        generator.addProvider(event.includeClient(), new ModLanguage(output));

        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(ModBlockLoot::new, LootContextParamSets.BLOCK)), lookup));
        generator.addProvider(event.includeServer(), new ModRecipes(output, lookup));
        generator.addProvider(event.includeServer(), new ModBlockTags(output, lookup, files));
    }
}
