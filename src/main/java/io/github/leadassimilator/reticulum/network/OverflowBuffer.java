package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Resources that could be neither delivered nor returned; retried first on later operations. Chemicals are kept by
 * id and amount (mB) so saves don't depend on Mekanism being installed.
 */
public final class OverflowBuffer {
    public static final Codec<OverflowBuffer> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(b -> stacked(b.items)),
            FluidStack.CODEC.listOf().optionalFieldOf("fluids", List.of()).forGetter(b -> List.copyOf(b.fluids)),
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("chemicals", Map.of()).forGetter(b -> b.chemicals)
    ).apply(i, OverflowBuffer::new));

    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();
    private final Map<Identifier, Long> chemicals = new LinkedHashMap<>();

    public OverflowBuffer() {
    }

    private OverflowBuffer(List<ItemStack> items, List<FluidStack> fluids, Map<Identifier, Long> chemicals) {
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

    public Map<Identifier, Long> chemicals() {
        return chemicals;
    }

    /**
     * Adds {@code stack} to {@code items}: topping up stacks of the same item, the rest as whole stacks. A stack is never
     * kept larger than the item stacks to (a save can't hold one, and would fail whole).
     */
    public static void addItem(List<ItemStack> items, ItemStack stack) {
        ItemStack left = stack.copy();
        for (ItemStack held : items) {
            if (left.isEmpty()) {
                return;
            }
            if (ItemStack.isSameItemSameComponents(held, left) && held.getCount() < held.getMaxStackSize()) {
                int moved = Math.min(left.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(moved);
                left.shrink(moved);
            }
        }
        while (!left.isEmpty()) {
            items.add(left.split(Math.max(1, left.getMaxStackSize())));
        }
    }

    /** {@code items} as stacks no larger than their items stack to, for saving. */
    private static List<ItemStack> stacked(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : items) {
            ItemStack left = stack.copy();
            while (!left.isEmpty()) {
                out.add(left.split(Math.max(1, left.getMaxStackSize())));
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return items.isEmpty() && fluids.isEmpty() && chemicals.isEmpty();
    }
}
