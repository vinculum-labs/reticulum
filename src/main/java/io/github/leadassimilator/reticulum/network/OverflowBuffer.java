package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Resources that could be neither delivered nor returned; retried first on later operations. Chemicals are kept by
 * id and amount (mB) so saves don't depend on Mekanism being installed.
 */
public final class OverflowBuffer {
    public static final Codec<OverflowBuffer> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(b -> List.copyOf(b.items)),
            FluidStack.CODEC.listOf().optionalFieldOf("fluids", List.of()).forGetter(b -> List.copyOf(b.fluids)),
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.LONG).optionalFieldOf("chemicals", Map.of()).forGetter(b -> b.chemicals)
    ).apply(i, OverflowBuffer::new));

    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();
    private final Map<ResourceLocation, Long> chemicals = new LinkedHashMap<>();

    public OverflowBuffer() {
    }

    private OverflowBuffer(List<ItemStack> items, List<FluidStack> fluids, Map<ResourceLocation, Long> chemicals) {
        this.items.addAll(items);
        this.fluids.addAll(fluids);
        this.chemicals.putAll(chemicals);
    }

    public List<ItemStack> items() {
        return items;
    }

    public List<FluidStack> fluids() {
        return fluids;
    }

    public Map<ResourceLocation, Long> chemicals() {
        return chemicals;
    }

    public boolean isEmpty() {
        return items.isEmpty() && fluids.isEmpty() && chemicals.isEmpty();
    }
}
