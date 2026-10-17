package io.github.leadassimilator.reticulum.client.web;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.leadassimilator.reticulum.integration.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Everything a filter can name, for the editor's picker: every item, fluid (sources only) and, with Mekanism, chemical,
 * each with its id, display name (in the player's language) and tags. Also every block that has an item, by its block
 * id with its item's name and the block's tags: the kinds of block a blueprint's slot can take. Built on the client,
 * where the names are.
 */
final class Catalog {
    private Catalog() {
    }

    static CompletableFuture<byte[]> build() {
        return Minecraft.getInstance().submit(() -> {
            JsonObject out = new JsonObject();
            JsonArray items = new JsonArray();
            BuiltInRegistries.ITEM.holders().forEach(holder -> {
                if (holder.value() != Items.AIR) {
                    items.add(entry(holder, new ItemStack(holder.value()).getHoverName().getString()));
                }
            });
            out.add("item", items);
            JsonArray blocks = new JsonArray();
            BuiltInRegistries.ITEM.forEach(item -> {
                if (item instanceof BlockItem blockItem) {
                    Holder.Reference<Block> block = blockItem.getBlock().builtInRegistryHolder();
                    blocks.add(entry(block, new ItemStack(item).getHoverName().getString()));
                }
            });
            out.add("block", blocks);
            JsonArray fluids = new JsonArray();
            BuiltInRegistries.FLUID.holders().forEach(holder -> {
                Fluid fluid = holder.value();
                if (fluid != Fluids.EMPTY && fluid.isSource(fluid.defaultFluidState())) {
                    fluids.add(entry(holder, new FluidStack(fluid, 1000).getHoverName().getString()));
                }
            });
            out.add("fluid", fluids);
            JsonArray chemicals = new JsonArray();
            if (Mods.CHEMICALS != null) {
                Mods.CHEMICALS.entries()
                        .forEach(e -> chemicals.add(entry(e.id().toString(), e.name(), e.tags().stream())));
            }
            out.add("chemical", chemicals);
            return out.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    private static <T> JsonObject entry(Holder.Reference<T> holder, String name) {
        return entry(holder.key().location().toString(), name, holder.tags().map(TagKey::location).map(Object::toString));
    }

    static JsonObject entry(String id, String name, Stream<String> tags) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("name", name);
        JsonArray array = new JsonArray();
        tags.sorted().forEach(array::add);
        entry.add("tags", array);
        return entry;
    }
}
