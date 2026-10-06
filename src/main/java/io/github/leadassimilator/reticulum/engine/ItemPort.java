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
    /**
     * The faces behind an unsided handler that may only show the inventory (a slot range, faces left to work out, the
     * server's fallback on), or null: what it takes in no slot at all, while a face would, goes in through them.
     */
    @javax.annotation.Nullable
    final List<IItemHandler> fallback;

    ItemPort(List<IItemHandler> handlers, List<IItemHandler> view, Optional<NodeSettings.SlotRange> range) {
        this(handlers, view, range, null);
    }

    ItemPort(List<IItemHandler> handlers, List<IItemHandler> view, Optional<NodeSettings.SlotRange> range,
            @javax.annotation.Nullable List<IItemHandler> fallback) {
        this.handlers = handlers;
        this.view = view.isEmpty() ? handlers : view;
        this.range = range;
        this.fallback = fallback == null || fallback.isEmpty() ? null : fallback;
    }

    /**
     * Whether {@code handler} only shows the block's slots: it holds something (anywhere, the range aside), and none of
     * it can be taken out or put back (simulated). Holding nothing, it can't be told (see {@link #takesAnywhere}).
     */
    static boolean readOnly(IItemHandler handler) {
        boolean holds = false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            holds = true;
            if (!handler.extractItem(slot, 1, true).isEmpty() || handler.insertItem(slot, stack.copyWithCount(1), true).isEmpty()) {
                return false;
            }
        }
        return holds;
    }

    boolean isEmpty() {
        return handlers.isEmpty();
    }

    boolean inRange(int slot) {
        return range.map(r -> r.contains(slot)).orElse(true);
    }

    long count(Predicate<ItemStack> which) {
        long total = 0;
        // Several faces may show one slot: its live stack is counted once.
        java.util.Set<ItemStack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (IItemHandler handler : view) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!inRange(slot)) {
                    continue;
                }
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && which.test(stack) && (view.size() < 2 || seen.add(stack))) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * How full the watched slots are, as a comparator sees it: each slot's share of what it can hold, averaged over
     * the slots; only stacks {@code which} matches count. Also whether any did.
     */
    double fullness(Predicate<ItemStack> which, boolean[] any) {
        double sum = 0;
        int slots = 0;
        // (Slots several faces show count once per face, filled or empty alike: the share stays right.)
        for (IItemHandler handler : view) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!inRange(slot)) {
                    continue;
                }
                slots++;
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && which.test(stack)) {
                    sum += stack.getCount() / (double) Math.max(1, Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize()));
                    any[0] = true;
                }
            }
        }
        return slots == 0 ? 0 : sum / slots;
    }

    boolean contains(ItemStack like) {
        return count(s -> ItemStack.isSameItemSameComponents(s, like)) > 0;
    }

    /**
     * Inserts into the port: first slots already holding the same item, then empty ones. Returns what didn't fit.
     */
    ItemStack insert(ItemStack stack, boolean simulate) {
        ItemStack left = insertThrough(handlers, stack, simulate);
        // None of it taken, the unsided handler would take it in no slot at all (the range aside), yet its faces would:
        // it only shows the inventory, so through the faces. A full block takes it through no face either: just full.
        return fallback != null && left.getCount() == stack.getCount() && onlyThroughFaces(stack) ? insertThrough(fallback, stack, simulate) : left;
    }

    /** Whether the handlers take none of these stacks in any slot, whatever the range (an unsided handler that only shows). */
    boolean refusesAll(List<ItemStack> stacks) {
        return fallback != null && stacks.stream().noneMatch(stack -> takesAnywhere(handlers, stack))
                && stacks.stream().anyMatch(stack -> takesAnywhere(fallback, stack));
    }

    /** The unsided handler takes {@code stack} in no slot at all, while a face would: it only shows the inventory. */
    private boolean onlyThroughFaces(ItemStack stack) {
        return onlyThroughFaces.computeIfAbsent(stack.getItem(), item -> !takesAnywhere(handlers, stack) && takesAnywhere(fallback, stack));
    }

    /** {@link #onlyThroughFaces} by kind of item, worked out once for this port. */
    private final java.util.Map<net.minecraft.world.item.Item, Boolean> onlyThroughFaces = new java.util.HashMap<>();

    /** Whether any slot of these handlers takes some of {@code stack} (simulated), whatever the range. */
    private static boolean takesAnywhere(List<IItemHandler> handlers, ItemStack stack) {
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (handler.insertItem(slot, stack, true).getCount() < stack.getCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    private ItemStack insertThrough(List<IItemHandler> handlers, ItemStack stack, boolean simulate) {
        if (!simulate || handlers.size() < 2) {
            return insert(handlers, stack, simulate);
        }
        // Several faces often wrap the same slots (a double chest, a machine's sides), and a simulated insert changes
        // nothing, so summing them would count the same room once per face: the real insert then refuses the rest.
        // Count the face taking the most instead; what the others would take more arrives on a later operation.
        ItemStack best = stack;
        for (IItemHandler handler : handlers) {
            ItemStack left = insert(List.of(handler), stack, true);
            if (left.getCount() < best.getCount()) {
                best = left;
            }
        }
        return best.copy();
    }

    private ItemStack insert(List<IItemHandler> handlers, ItemStack stack, boolean simulate) {
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
            if (sheet.broken(limit)) {
                return 0;
            }
            Filters.ItemFilter filter = sheet.itemFilter(limit);
            if (!filter.test(stack, context)) {
                continue;
            }
            long present = switch (limit.scope()) {
                case PER_TYPE -> count(s -> ItemStack.isSameItemSameComponents(s, stack));
                // In total: everything in the port, whatever the rule's filter (which only says what it applies to).
                case TOTAL -> count(s -> true);
                case FILTER -> count(s -> filter.test(s, context));
            };
            long room = keep ? present - limit.amount() : limit.amount() - present;
            allowed = Math.min(allowed, Math.max(0, room));
        }
        return allowed;
    }
}
