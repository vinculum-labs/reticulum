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

/**
 * Recipes. Cables and connectors are cheap early-game iron and redstone; the controller needs a diamond; each antenna
 * tier costs more, since tier 2 reaches the whole dimension and tier 3 every dimension.
 */
public class ModRecipes extends RecipeProvider {
    public ModRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        // Blank facades, painted afterwards to look like any block.
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.FACADE.get(), 8)
                .pattern(" N ").pattern("NCN").pattern(" N ")
                .define('N', Tags.Items.NUGGETS_IRON).define('C', ModItems.CABLE.get())
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
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
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.STATUS_BEACON.get())
                .pattern("GGG").pattern("GLG").pattern("ICI")
                .define('G', Tags.Items.GLASS_BLOCKS).define('L', Items.REDSTONE_LAMP).define('I', Tags.Items.INGOTS_IRON)
                .define('C', ModItems.CABLE.get())
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.NETWORK_TOOL.get())
                .pattern(" IC").pattern(" SI").pattern("S  ")
                .define('I', Tags.Items.INGOTS_IRON).define('C', ModItems.CABLE.get()).define('S', ItemTags.PLANKS)
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.ANTENNA_1.get())
                .pattern(" R ").pattern(" I ").pattern("ICI")
                .define('R', Tags.Items.DUSTS_REDSTONE).define('I', Tags.Items.INGOTS_IRON).define('C', ModItems.CABLE.get())
                .unlockedBy("has_cable", has(ModItems.CABLE.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.ANTENNA_2.get())
                .pattern(" E ").pattern("DAD").pattern(" G ")
                .define('E', Tags.Items.ENDER_PEARLS).define('D', Tags.Items.GEMS_DIAMOND).define('G', Tags.Items.STORAGE_BLOCKS_GOLD)
                .define('A', ModItems.ANTENNA_1.get())
                .unlockedBy("has_antenna", has(ModItems.ANTENNA_1.get()))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModItems.ANTENNA_3.get())
                .pattern(" E ").pattern("OAO").pattern(" N ")
                .define('E', Items.ENDER_EYE).define('O', Items.CRYING_OBSIDIAN).define('N', Tags.Items.INGOTS_NETHERITE)
                .define('A', ModItems.ANTENNA_2.get())
                .unlockedBy("has_antenna", has(ModItems.ANTENNA_2.get()))
                .save(output);
    }
}
