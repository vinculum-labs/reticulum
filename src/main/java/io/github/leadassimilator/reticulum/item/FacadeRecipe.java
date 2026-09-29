package io.github.leadassimilator.reticulum.item;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.registry.ModItems;
import io.github.leadassimilator.reticulum.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** A cable and any full block, anywhere in the grid, make four facades of that block. */
public class FacadeRecipe extends CustomRecipe {
    public static final int COUNT = 4;

    public FacadeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return facadeBlock(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        BlockState state = facadeBlock(input);
        return state == null ? ItemStack.EMPTY : FacadeItem.of(state, COUNT);
    }

    /** The block the facades will look like, when the grid holds exactly one cable and one block that can be a facade. */
    @Nullable
    static BlockState facadeBlock(CraftingInput input) {
        int cables = 0;
        BlockState block = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.CABLE.get())) {
                cables++;
            } else if (block == null && stack.getItem() instanceof BlockItem item && FacadeItem.canBeFacade(item.getBlock().defaultBlockState())) {
                block = item.getBlock().defaultBlockState();
            } else {
                return null;
            }
        }
        return cables == 1 ? block : null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.FACADE.get();
    }
}
