package io.github.leadassimilator.reticulum.integration.jei;

import java.util.List;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.client.screen.FacadeScreen;
import io.github.leadassimilator.reticulum.item.FacadeItem;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** JEI beside the facade screen: drag a block from JEI's list onto it (or shift-click one) to paint the facades in hand. */
@JeiPlugin
public class ReticulumJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(FacadeScreen.class, screen -> new IGuiProperties() {
            @Override
            public Class<? extends Screen> screenClass() {
                return FacadeScreen.class;
            }

            @Override
            public int guiLeft() {
                return screen.guiLeft();
            }

            @Override
            public int guiTop() {
                return screen.guiTop();
            }

            @Override
            public int guiXSize() {
                return FacadeScreen.guiWidth();
            }

            @Override
            public int guiYSize() {
                return FacadeScreen.guiHeight();
            }

            @Override
            public int screenWidth() {
                return FacadeScreen.screenWidth();
            }

            @Override
            public int screenHeight() {
                return FacadeScreen.screenHeight();
            }
        });
        registration.addGhostIngredientHandler(FacadeScreen.class, new IGhostIngredientHandler<>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(FacadeScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                if (block(ingredient) == null) {
                    return List.of();
                }
                return List.of(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return screen.dropArea();
                    }

                    @Override
                    public void accept(I dropped) {
                        screen.choose(block(ingredient));
                    }
                });
            }

            @Override
            public void onComplete() {
            }
        });
    }

    /** The block an ingredient places, when it can be a facade. */
    private static Block block(ITypedIngredient<?> ingredient) {
        ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY);
        return stack.getItem() instanceof BlockItem item && FacadeItem.canBeFacade(item.getBlock().defaultBlockState()) ? item.getBlock() : null;
    }
}
