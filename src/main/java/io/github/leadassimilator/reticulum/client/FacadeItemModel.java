package io.github.leadassimilator.reticulum.client;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.FacadeItem;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * A painted facade item looks like the block it imitates (in inventories, in hand and on the ground), with a small
 * badge in inventories so it isn't mistaken for the real block. A blank facade keeps its own texture.
 */
public final class FacadeItemModel extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation BADGE = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "textures/gui/facade_badge.png");

    private final ItemOverrides overrides = new ItemOverrides() {
        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
            BlockState state = FacadeItem.state(stack);
            Item item = state == null ? Items.AIR : state.getBlock().asItem();
            if (item == Items.AIR) {
                return originalModel.getOverrides().resolve(originalModel, stack, level, entity, seed);
            }
            return Minecraft.getInstance().getItemRenderer().getModel(new ItemStack(item), level, entity, seed);
        }
    };

    private FacadeItemModel(BakedModel original) {
        super(original);
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    public static void wrap(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation location = ModelResourceLocation.inventory(ModItems.FACADE.getId());
        event.getModels().computeIfPresent(location, (key, model) -> new FacadeItemModel(model));
    }

    /** The badge: a small "F" in the top-left corner of painted facades, clear of the count and durability bar. */
    public static void registerDecorator(RegisterItemDecorationsEvent event) {
        event.register(ModItems.FACADE.get(), (graphics, font, stack, x, y) -> {
            if (FacadeItem.state(stack) == null) {
                return false;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.blit(BADGE, x, y, 0, 0, 8, 8, 8, 8);
            graphics.pose().popPose();
            return true;
        });
    }
}
