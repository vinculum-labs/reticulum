package io.github.leadassimilator.reticulum.engine;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The resolved item handlers of a source or sink, limited to its slot range. Items move through {@code handlers};
 * counts (keep rules, caps, in:sink) read {@code view}, which may be the block's unsided handler.
 */
final class ItemPort {
    final List<IItemHandler> handlers;
    private final List<IItemHandler> view;
    private final Optional<NodeSettings.SlotRange> range;

    ItemPort(List<IItemHandler> handlers, List<IItemHandler> view, Optional<NodeSettings.SlotRange> range) {
        this.handlers = handlers;
        this.view = view.isEmpty() ? handlers : view;
        this.range = range;
    }

    boolean isEmpty() {
        return handlers.isEmpty();
    }

    boolean inRange(int slot) {
        return range.map(r -> r.contains(slot)).orElse(true);
    }

    long count(Predicate<ItemStack> which) {
        long total = 0;
        for (IItemHandler handler : view) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!inRange(slot)) {
                    continue;
                }
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && which.test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    boolean contains(ItemStack like) {
        return count(s -> ItemStack.isSameItemSameComponents(s, like)) > 0;
    }

    /**
     * Inserts into the port: first slots already holding the same item, then empty ones. Returns what didn't fit.
     */
    ItemStack insert(ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (IItemHandler handler : handlers) {
                for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
                    if (!inRange(slot)) {
                        continue;
                    }
                    ItemStack present = handler.getStackInSlot(slot);
                    boolean matching = !present.isEmpty() && ItemStack.isSameItemSameComponents(present, remaining);
                    if (pass == 0 ? matching : present.isEmpty()) {
                        remaining = handler.insertItem(slot, remaining, simulate);
                    }
                }
            }
        }
        return remaining;
    }

    /**
     * How many of {@code stack} may leave (keep rules) or arrive (caps). Keep rules and caps share one shape: the
     * counted amount may not go below (keep) or above (cap) the rule's amount.
     */
    long limit(List<NodeSettings.Limit> limits, CompiledSheet sheet, ItemStack stack, Filters.Context context, boolean keep) {
        long allowed = Long.MAX_VALUE;
        for (NodeSettings.Limit limit : limits) {
            Filters.ItemFilter filter = sheet.itemFilter(limit);
            if (!filter.test(stack, context)) {
                continue;
            }
            long present = switch (limit.scope()) {
                case PER_TYPE -> count(s -> ItemStack.isSameItemSameComponents(s, stack));
                case TOTAL, FILTER -> count(s -> filter.test(s, context));
            };
            long room = keep ? present - limit.amount() : limit.amount() - present;
            allowed = Math.min(allowed, Math.max(0, room));
        }
        return allowed;
    }
}
