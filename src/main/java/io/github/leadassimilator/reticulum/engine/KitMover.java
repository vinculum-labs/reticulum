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
        if (kit.lines().stream().noneMatch(line -> line.count() > 0)) {
            return false;
        }
        // Compiled with the sheet; a line whose filter failed matches nothing (and the editor shows why).
        List<Filters.ItemFilter> lineFilters = new ArrayList<>();
        for (NodeSettings.KitLine line : kit.lines()) {
            lineFilters.add(sheet.itemFilter(line.filter()));
        }
        boolean any = false;
        for (int done = 0; done < Math.max(1, kit.maxKits()); done++) {
            // Sinks in splitter order, each with the routes that reach it from the kit's sources: worked out for each
            // kit, as round robin and weighted splitters turn after every one delivered.
            Map<UUID, List<CompiledSheet.Route>> bySink = new LinkedHashMap<>();
            for (CompiledSheet.Route route : ctx.runtime().ordered(routes)) {
                bySink.computeIfAbsent(route.sink().id(), k -> new ArrayList<>()).add(route);
            }
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
        ItemPort to = ctx.runtime().itemPort(ctx.server(), ctx.network(), first.sinkSettings().port());
        if (to.isEmpty() || level == null) {
            ctx.runtime().status(sheet.owner(first.kit().id()), Status.TARGET_OFFLINE);
            return false;
        }
        Filters.Context context = new Filters.Context(level, to::contains, null);

        UUID kitNode = sheet.owner(first.kit().id());
        List<Take> takes = planExtraction(ctx, sheet, kit, lineFilters, routes, context);
        if (takes == null) {
            ctx.runtime().status(kitNode, Status.WAITING_FOR_PARTS);
            return false;
        }
        if (!withinRateLimits(ctx, takes)) {
            ctx.runtime().status(kitNode, Status.RATE_LIMITED);
            return false;
        }
        if (!withinCaps(sheet, to, first.sinkSettings().caps(), takes, context)) {
            ctx.runtime().status(kitNode, Status.CAP_REACHED);
            return false;
        }
        // Planned across every face of the sink, unless that went wrong before (see below): then through one face first,
        // and across faces still when no one face takes it all (a furnace's ore and fuel go in through different faces).
        boolean oneFace = ctx.runtime().kitsOneFace().contains(kitNode);
        List<Put> puts = oneFace ? planOneFace(to, takes) : null;
        if (puts == null) {
            puts = planInsertion(to, to.handlers, takes);
            if (puts != null && !verify(takes, puts)) {
                puts = null;
            }
            oneFace = false;
        }
        if (puts == null) {
            ctx.runtime().status(kitNode, Status.NO_ROOM_FOR_KIT);
            return false;
        }
        boolean moved = commit(ctx, takes, puts);
        ctx.runtime().status(kitNode, moved ? null : Status.NO_ROOM_FOR_KIT);
        // Refused after planning across faces: two faces wrap the same slots (a block reached by two connectors), so an
        // empty slot was counted twice. From now on this kit plans through one face at a time.
        if (!moved && !oneFace && to.handlers.size() > 1) {
            ctx.runtime().kitsOneFace().add(kitNode);
        }
        return moved;
    }

    // ---------------------------------------------------------------- step 1: plan the extraction

    @Nullable
    private static List<Take> planExtraction(NetworkRuntime.Context ctx, CompiledSheet sheet, NodeSettings.KitSettings kit,
            List<Filters.ItemFilter> lineFilters, List<CompiledSheet.Route> routes, Filters.Context context) {
        List<Take> takes = new ArrayList<>();
        Map<IItemHandler, Map<Integer, Integer>> shadow = new HashMap<>();
        Map<ItemStack, Integer> claimedStacks = new java.util.IdentityHashMap<>();
        for (int line = 0; line < kit.lines().size(); line++) {
            int needed = kit.lines().get(line).count();
            Filters.ItemFilter lineFilter = lineFilters.get(line);
            for (CompiledSheet.Route route : routes) {
                if (needed <= 0) {
                    break;
                }
                NodeSettings.SourceSettings source = route.sourceSettings();
                ItemPort from = ctx.runtime().itemPort(ctx.server(), ctx.network(), source.port());
                for (IItemHandler handler : from.handlers) {
                    for (int slot = 0; slot < handler.getSlots() && needed > 0; slot++) {
                        if (!from.inRange(slot)) {
                            continue;
                        }
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty() || !lineFilter.test(stack, context) || !ItemMover.passes(route.gates(), stack, context)) {
                            continue;
                        }
                        boolean live = handler.getStackInSlot(slot) == stack;
                        int claimed = live ? claimedStacks.getOrDefault(stack, 0)
                                : shadow.computeIfAbsent(handler, h -> new HashMap<>()).getOrDefault(slot, 0);
                        long kept = keepAllowed(sheet, from, source, stack, context, takes);
                        int take = (int) Math.min(Math.min(needed, stack.getCount() - claimed), Math.max(0, kept));
                        if (take <= 0) {
                            continue;
                        }
                        if (live) {
                            claimedStacks.merge(stack, take, Integer::sum);
                        } else {
                            shadow.computeIfAbsent(handler, h -> new HashMap<>()).merge(slot, take, Integer::sum);
                        }
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

    /** How many of {@code stack} the source's keep rules still allow, after what this kit already plans to take. */
    private static long keepAllowed(CompiledSheet sheet, ItemPort from, NodeSettings.SourceSettings source, ItemStack stack,
            Filters.Context context, List<Take> takes) {
        long allowed = Long.MAX_VALUE;
        for (NodeSettings.Limit rule : source.keep()) {
            if (sheet.broken(rule)) {
                return 0;
            }
            Filters.ItemFilter filter = sheet.itemFilter(rule);
            if (!filter.test(stack, context)) {
                continue;
            }
            long planned = 0;
            for (Take take : takes) {
                boolean counted = switch (rule.scope()) {
                    case PER_TYPE -> ItemStack.isSameItemSameComponents(take.stack, stack);
                    case TOTAL -> true;
                    case FILTER -> filter.test(take.stack, context);
                };
                if (counted && take.route.sourceSettings().port().equals(source.port())) {
                    planned += take.stack.getCount();
                }
            }
            allowed = Math.min(allowed, from.limit(List.of(rule), sheet, stack, context, true) - planned);
        }
        return allowed;
    }

    private static boolean withinRateLimits(NetworkRuntime.Context ctx, List<Take> takes) {
        Map<Node, Long> perLimit = new LinkedHashMap<>();
        for (Take take : takes) {
            for (Node limit : take.route.rateLimits()) {
                perLimit.merge(limit, (long) take.stack.getCount(), Long::sum);
            }
        }
        for (Map.Entry<Node, Long> entry : perLimit.entrySet()) {
            if (ctx.runtime().available(List.of(entry.getKey())) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Every cap must have room for everything in the kit it counts. */
    private static boolean withinCaps(CompiledSheet sheet, ItemPort to, List<NodeSettings.Limit> caps, List<Take> takes, Filters.Context context) {
        for (NodeSettings.Limit cap : caps) {
            // A cap whose filter doesn't compile lets nothing in, as for single moves.
            if (sheet.broken(cap)) {
                return false;
            }
            Filters.ItemFilter filter = sheet.itemFilter(cap);
            for (Take take : takes) {
                if (!filter.test(take.stack, context)) {
                    continue;
                }
                long incoming = 0;
                for (Take other : takes) {
                    boolean counted = switch (cap.scope()) {
                        case PER_TYPE -> ItemStack.isSameItemSameComponents(other.stack, take.stack);
                        case TOTAL -> true;
                        case FILTER -> filter.test(other.stack, context);
                    };
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

    /** The kit put in through one face, the first that takes all of it. */
    @Nullable
    private static List<Put> planOneFace(ItemPort to, List<Take> takes) {
        for (IItemHandler handler : to.handlers) {
            List<Put> puts = planInsertion(to, List.of(handler), takes);
            if (puts != null && verify(takes, puts)) {
                return puts;
            }
        }
        return null;
    }

    @Nullable
    private static List<Put> planInsertion(ItemPort to, List<IItemHandler> handlers, List<Take> takes) {
        List<Put> slots = new ArrayList<>();
        java.util.Set<ItemStack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!to.inRange(slot)) {
                    continue;
                }
                ItemStack contents = handler.getStackInSlot(slot);
                // A live, non-empty stack seen before is the same slot through another face (empty slots all share
                // one EMPTY stack, so they can't be told apart this way; the simulation before committing catches those).
                if (!contents.isEmpty() && handler.getStackInSlot(slot) == contents && !seen.add(contents)) {
                    continue;
                }
                slots.add(new Put(handler, slot, contents.copy()));
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
                    if (current.getCount() > limit) {
                        // Already more than a stack: storage that ignores stack sizes, limited only by the slot.
                        limit = put.handler.getSlotLimit(put.slot);
                    }
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
        // Each item for its own chart (and the route's totals), then each route on once.
        java.util.Set<CompiledSheet.Route> moved = new java.util.LinkedHashSet<>();
        for (Take take : takes) {
            ctx.runtime().delivered(take.route, take.stack.getCount(),
                    "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(take.stack.getItem()));
            moved.add(take.route);
        }
        moved.forEach(route -> ctx.runtime().advance(route));
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
