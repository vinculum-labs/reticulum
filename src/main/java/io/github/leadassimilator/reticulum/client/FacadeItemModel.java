package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.FacadeItem;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;

/**
 * A painted facade item looks like the block it imitates (in inventories, in hand and on the ground), with a small
 * badge in inventories so it isn't mistaken for the real block. A blank facade shows {@code blank}, its own texture.
 * The facade's item definition uses this model type, {@code reticulum:facade} (see ModModels).
 */
public record FacadeItemModel(ItemModel blank) implements ItemModel {
    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "facade");
    private static final ResourceLocation BADGE = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "textures/gui/facade_badge.png");

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
            @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        BlockState facade = FacadeItem.state(stack);
        Item item = facade == null ? Items.AIR : facade.getBlock().asItem();
        if (item == Items.AIR) {
            blank.update(state, stack, resolver, context, level, owner, seed);
        } else {
            resolver.appendItemLayers(state, new ItemStack(item), context, level, owner, seed);
        }
    }

    /** As written in the item definition: {@code blank} is the model of a blank facade. */
    public record Unbaked(ItemModel.Unbaked blank) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemModels.CODEC.fieldOf("blank").forGetter(Unbaked::blank)).apply(instance, Unbaked::new));

        @Override
        public MapCodec<Unbaked> type() {
            return CODEC;
        }

        @Override
        public ItemModel bake(BakingContext context) {
            return new FacadeItemModel(blank.bake(context));
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            blank.resolveDependencies(resolver);
        }
    }

    public static void register(RegisterItemModelsEvent event) {
        event.register(TYPE, Unbaked.CODEC);
    }

    /** The badge: a small "F" in the top-left corner of painted facades, clear of the count and durability bar. */
    public static void registerDecorator(RegisterItemDecorationsEvent event) {
        event.register(ModItems.FACADE.get(), (graphics, font, stack, x, y) -> {
            if (FacadeItem.state(stack) == null) {
                return false;
            }
            // Three-quarter size (6x6 GUI pixels, still sharp at larger GUI scales) and half see-through over the block.
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(0.75f, 0.75f);
            graphics.blit(RenderPipelines.GUI_TEXTURED, BADGE, 0, 0, 0, 0, 8, 8, 8, 8, 0x80FFFFFF);
            graphics.pose().popMatrix();
            return true;
        });
    }
}
