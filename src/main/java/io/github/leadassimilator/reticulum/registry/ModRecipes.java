package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.FacadeRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Reticulum.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<FacadeRecipe>> FACADE = SERIALIZERS.register("facade",
            () -> new SimpleCraftingRecipeSerializer<>(FacadeRecipe::new));

    private ModRecipes() {
    }
}
