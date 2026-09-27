package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * All-or-nothing item sets. NeoForge 1.21.1 has no transactions and item-handler simulations don't add up across
 * several stacks, so one kit is moved in four steps:
 * <ol>
 * <li>plan the extraction, with a shadow count per slot so two parts never claim the same items;</li>
 * <li>plan the insertion against a copy of the sink's slots (limits, validity, stacking);</li>
 * <li>check the plan against the real handlers, slot by slot, with the planned totals;</li>
 * <li>commit, and if the sink still refuses something, take back what this kit inserted and return it all.</li>
 * </ol>
 */
final class KitMover {
    private KitMover() {
    }

    /** Items planned to leave one source slot. */
    private record Take(IItemHandler handler, int slot, ItemStack stack, CompiledSheet.Route route) {
    }

    /** Items planned to enter one sink slot. */
    private static final class Put {
        final IItemHandler handler;
        final int slot;
        ItemStack contents;
        ItemStack incoming = ItemStack.EMPTY;

        Put(IItemHandler handler, int slot, ItemStack contents) {
            this.handler = handler;
            this.slot = slot;
            this.contents = contents;
        }
    }

    /** Returns whether at least one kit moved. */
    static boolean operate(NetworkRuntime.Context ctx, CompiledSheet sheet, Node kitNode, List<CompiledSheet.Route> routes) {
        NodeSettings.KitSettings kit = (NodeSettings.KitSettings) kitNode.settings();
        List<Filters.ItemFilter> lineFilters = new ArrayList<>();
        for (NodeSettings.KitLine line : kit.lines()) {
            try {
                lineFilters.add(Filters.items(line.filter(), ctx.network().filters()));
            } catch (FilterException e) {
                return false;
            }
        }
        // Sinks in splitter order, each with the routes that reach it from the kit's sources.
        Map<UUID, List<CompiledSheet.Route>> bySink = new LinkedHashMap<>();
        for (CompiledSheet.Route route : routes) {
            bySink.computeIfAbsent(route.sink().id(), k -> new ArrayList<>()).add(route);
        }
        boolean any = false;
        for (int done = 0; done < Math.max(1, kit.maxKits()); done++) {
            boolean delivered = false;
            for (List<CompiledSheet.Route> sinkRoutes : bySink.values()) {
                if (moveOne(ctx, sheet, kit, lineFilters, sinkRoutes)) {
                    delivered = true;
                    break;
                }
            }
            if (!delivered) {
                return any;
            }
            any = true;
        }
        return any;
    }

    private static boolean moveOne(NetworkRuntime.Context ctx, CompiledSheet sheet, NodeSettings.KitSettings kit,
            List<Filters.ItemFilter> lineFilters, List<CompiledSheet.Route> routes) {
        CompiledSheet.Route first = routes.getFirst();
        ServerLevel level = ctx.server().getLevel(first.sinkSettings().port().device().dimension());
        ItemPort to = new ItemPort(ctx.runtime().items(ctx.server(), ctx.network(), first.sinkSettings().port()), first.sinkSettings().port().slots());
        if (to.isEmpty() || level == null) {
            return false;
        }
        Filters.Context context = new Filters.Context(level, to::contains, null);

        List<Take> takes = planExtraction(ctx, sheet, kit, lineFilters, routes, context);
        if (takes == null || !withinRateLimits(ctx, takes) || !withinCaps(sheet, to, first.sinkSettings().caps(), takes, context)) {
            return false;
        }
        List<Put> puts = planInsertion(to, takes);
        if (puts == null || !verify(takes, puts)) {
            return false;
        }
        return commit(ctx, takes, puts);
    }

    // ---------------------------------------------------------------- step 1: plan the extraction

    @Nullable
    private static List<Take> planExtraction(NetworkRuntime.Context ctx, CompiledSheet sheet, NodeSettings.KitSettings kit,
            List<Filters.ItemFilter> lineFilters, List<CompiledSheet.Route> routes, Filters.Context context) {
        List<Take> takes = new ArrayList<>();
        Map<IItemHandler, Map<Integer, Integer>> shadow = new HashMap<>();
        for (int line = 0; line < kit.lines().size(); line++) {
            int needed = kit.lines().get(line).count();
            Filters.ItemFilter lineFilter = lineFilters.get(line);
            for (CompiledSheet.Route route : routes) {
                if (needed <= 0) {
                    break;
                }
                NodeSettings.SourceSettings source = route.sourceSettings();
                ItemPort from = new ItemPort(ctx.runtime().items(ctx.server(), ctx.network(), source.port()), source.port().slots());
                for (IItemHandler handler : from.handlers) {
                    for (int slot = 0; slot < handler.getSlots() && needed > 0; slot++) {
                        if (!from.inRange(slot)) {
                            continue;
                        }
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty() || !lineFilter.test(stack, context) || !ItemMover.passes(route.gates(), stack, context)) {
                            continue;
                        }
                        int claimed = shadow.computeIfAbsent(handler, h -> new HashMap<>()).getOrDefault(slot, 0);
                        long kept = from.limit(source.keep(), sheet, stack, context, true) - plannedLike(takes, stack);
                        int take = (int) Math.min(Math.min(needed, stack.getCount() - claimed), Math.max(0, kept));
                        if (take <= 0) {
                            continue;
                        }
                        shadow.get(handler).merge(slot, take, Integer::sum);
                        takes.add(new Take(handler, slot, stack.copyWithCount(take), route));
                        needed -= take;
                    }
                }
            }
            if (needed > 0) {
                return null;
            }
        }
        return takes;
    }

    private static long plannedLike(List<Take> takes, ItemStack like) {
        long n = 0;
        for (Take take : takes) {
            if (ItemStack.isSameItemSameComponents(take.stack, like)) {
                n += take.stack.getCount();
            }
        }
        return n;
    }

    private static boolean withinRateLimits(NetworkRuntime.Context ctx, List<Take> takes) {
        Map<CompiledSheet.Route, Long> perRoute = new LinkedHashMap<>();
        for (Take take : takes) {
            perRoute.merge(take.route, (long) take.stack.getCount(), Long::sum);
        }
        for (Map.Entry<CompiledSheet.Route, Long> entry : perRoute.entrySet()) {
            if (ctx.runtime().available(entry.getKey().rateLimits()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Every cap must have room for everything in the kit it counts. */
    private static boolean withinCaps(CompiledSheet sheet, ItemPort to, List<NodeSettings.Limit> caps, List<Take> takes, Filters.Context context) {
        for (NodeSettings.Limit cap : caps) {
            Filters.ItemFilter filter = sheet.itemFilter(cap);
            for (Take take : takes) {
                if (!filter.test(take.stack, context)) {
                    continue;
                }
                long incoming = 0;
                for (Take other : takes) {
                    boolean counted = cap.scope() == NodeSettings.Scope.PER_TYPE
                            ? ItemStack.isSameItemSameComponents(other.stack, take.stack)
                            : filter.test(other.stack, context);
                    if (counted) {
                        incoming += other.stack.getCount();
                    }
                }
                if (to.limit(List.of(cap), sheet, take.stack, context, false) < incoming) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- step 2: plan the insertion

    @Nullable
    private static List<Put> planInsertion(ItemPort to, List<Take> takes) {
        List<Put> slots = new ArrayList<>();
        for (IItemHandler handler : to.handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (to.inRange(slot)) {
                    slots.add(new Put(handler, slot, handler.getStackInSlot(slot).copy()));
                }
            }
        }
        for (Take take : takes) {
            ItemStack remaining = take.stack.copy();
            for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
                for (Put put : slots) {
                    if (remaining.isEmpty()) {
                        break;
                    }
                    ItemStack current = put.contents;
                    boolean matching = !current.isEmpty() && ItemStack.isSameItemSameComponents(current, remaining);
                    if (!(pass == 0 ? matching : current.isEmpty()) || !put.handler.isItemValid(put.slot, remaining)) {
                        continue;
                    }
                    int limit = Math.min(put.handler.getSlotLimit(put.slot), remaining.getMaxStackSize());
                    int room = limit - current.getCount();
                    if (room <= 0) {
                        continue;
                    }
                    int moved = Math.min(room, remaining.getCount());
                    put.contents = current.isEmpty() ? remaining.copyWithCount(moved) : current.copyWithCount(current.getCount() + moved);
                    put.incoming = put.incoming.isEmpty() ? remaining.copyWithCount(moved) : put.incoming.copyWithCount(put.incoming.getCount() + moved);
                    remaining.shrink(moved);
                }
            }
            if (!remaining.isEmpty()) {
                return null;
            }
        }
        slots.removeIf(put -> put.incoming.isEmpty());
        return slots;
    }

    // ---------------------------------------------------------------- step 3: check against the real handlers

    private static boolean verify(List<Take> takes, List<Put> puts) {
        Map<IItemHandler, Map<Integer, Integer>> totals = new HashMap<>();
        for (Take take : takes) {
            totals.computeIfAbsent(take.handler, h -> new HashMap<>()).merge(take.slot, take.stack.getCount(), Integer::sum);
        }
        for (Map.Entry<IItemHandler, Map<Integer, Integer>> handler : totals.entrySet()) {
            for (Map.Entry<Integer, Integer> slot : handler.getValue().entrySet()) {
                if (handler.getKey().extractItem(slot.getKey(), slot.getValue(), true).getCount() != slot.getValue()) {
                    return false;
                }
            }
        }
        for (Put put : puts) {
            if (!put.handler.insertItem(put.slot, put.incoming, true).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- step 4: commit, or roll back

    private static boolean commit(NetworkRuntime.Context ctx, List<Take> takes, List<Put> puts) {
        List<ItemStack> extracted = new ArrayList<>();
        for (Take take : takes) {
            ItemStack got = take.handler.extractItem(take.slot, take.stack.getCount(), false);
            extracted.add(got);
            if (got.getCount() != take.stack.getCount()) {
                returnAll(ctx, takes, extracted);
                return false;
            }
        }
        List<ItemStack> inserted = new ArrayList<>();
        for (int i = 0; i < puts.size(); i++) {
            Put put = puts.get(i);
            ItemStack refused = put.handler.insertItem(put.slot, put.incoming, false);
            inserted.add(put.incoming.copyWithCount(put.incoming.getCount() - refused.getCount()));
            if (!refused.isEmpty()) {
                // The sink changed its mind after a successful simulation: take back what this kit put in.
                List<ItemStack> back = new ArrayList<>();
                for (int j = 0; j <= i; j++) {
                    Put done = puts.get(j);
                    int count = inserted.get(j).getCount();
                    if (count > 0) {
                        back.add(done.handler.extractItem(done.slot, count, false));
                    }
                }
                int stuck = countAll(inserted) - countAll(back);
                if (stuck > 0) {
                    Reticulum.LOGGER.warn("Kit rollback could not take back {} items from the sink; they stay there", stuck);
                }
                // In hand now: everything extracted, less what went into the sink, plus what came back out of it.
                List<ItemStack> pool = new ArrayList<>();
                extracted.forEach(stack -> pool.add(stack.copy()));
                subtract(pool, inserted);
                pool.addAll(back);
                returnAll(ctx, takes, pool);
                return false;
            }
        }
        Map<CompiledSheet.Route, Long> perRoute = new LinkedHashMap<>();
        for (Take take : takes) {
            perRoute.merge(take.route, (long) take.stack.getCount(), Long::sum);
        }
        perRoute.forEach((route, count) -> {
            ctx.runtime().consume(route.rateLimits(), count);
            ctx.runtime().advance(route);
        });
        return true;
    }

    /** Puts items back into the source slots they came from, matching by item; the rest goes to overflow. */
    private static void returnAll(NetworkRuntime.Context ctx, List<Take> takes, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack left = stack.copy();
            for (Take take : takes) {
                if (left.isEmpty()) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(take.stack, left)) {
                    left = take.handler.insertItem(take.slot, left, false);
                }
            }
            if (!left.isEmpty()) {
                ItemMover.giveBack(ctx, takes.getFirst().handler, takes.getFirst().slot, left);
            }
        }
    }

    private static void subtract(List<ItemStack> pool, List<ItemStack> remove) {
        for (ItemStack stack : remove) {
            int left = stack.getCount();
            for (ItemStack p : pool) {
                if (left <= 0) {
                    break;
                }
                if (!p.isEmpty() && ItemStack.isSameItemSameComponents(p, stack)) {
                    int n = Math.min(left, p.getCount());
                    p.shrink(n);
                    left -= n;
                }
            }
        }
        pool.removeIf(ItemStack::isEmpty);
    }

    private static int countAll(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack stack : stacks) {
            n += stack.getCount();
        }
        return n;
    }
}
