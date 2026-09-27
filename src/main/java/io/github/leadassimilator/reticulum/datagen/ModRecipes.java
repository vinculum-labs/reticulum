package io.github.leadassimilator.reticulum.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

/** Placeholder recipes so the mod is playable in survival; balancing comes in phase 6. */
public class ModRecipes extends RecipeProvider {
    public ModRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.CABLE.get(), 16)
                .pattern("NNN").pattern("RGR").pattern("NNN")
                .define('N', Tags.Items.NUGGETS_IRON).define('R', Tags.Items.DUSTS_REDSTONE).define('G', Tags.Items.GLASS_PANES)
                .unlockedBy("has_redstone", has(Tags.Items.DUSTS_REDSTONE))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.CONNECTOR.get(), 2)
                .pattern(" C ").pattern("CHC").pattern(" C ")
                .define('C', ModItems.CABLE.get()).define('H', Items.HOPPER)
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.CONTROLLER.get())
                .pattern("IRI").pattern("CDC").pattern("IRI")
                .define('I', Tags.Items.INGOTS_IRON).define('R', Tags.Items.STORAGE_BLOCKS_REDSTONE)
                .define('C', ModItems.CABLE.get()).define('D', Tags.Items.GEMS_DIAMOND)
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.NETWORK_TOOL.get())
                .pattern(" IC").pattern(" SI").pattern("S  ")
                .define('I', Tags.Items.INGOTS_IRON).define('C', ModItems.CABLE.get()).define('S', ItemTags.PLANKS)
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
    }
}
