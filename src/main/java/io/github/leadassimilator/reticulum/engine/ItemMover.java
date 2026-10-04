package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Item operations. Each source runs once per interval: it walks its slots, and every stack goes down the first
 * routes (in splitter order) whose filters it passes, as far as keep rules, caps, rate limits and the per-operation
 * amount allow. Every move is simulated first; what the sink then refuses goes back to the source, and only what
 * the source also refuses goes to the overflow buffer.
 */
final class ItemMover {
    private ItemMover() {
    }

    static void run(NetworkRuntime.Context ctx, CompiledSheet sheet) {
        if (!ctx.runtime().powered()) {
            for (CompiledSheet.Source source : sheet.sources()) {
                if (source.resource() == ResourceKind.ITEM) {
                    ctx.runtime().status(sheet.owner(source.node().id()), Status.NO_POWER);
                }
            }
            return;
        }
        if (Overflow.itemsFull(ctx.network())) {
            for (CompiledSheet.Source source : sheet.sources()) {
                if (source.resource() == ResourceKind.ITEM) {
                    ctx.runtime().status(sheet.owner(source.node().id()), Status.OVERFLOW_FULL);
                }
            }
            return;
        }
        // Kits can gather their parts from several sources, so kit routes are grouped per kit across the sheet.
        Map<UUID, List<CompiledSheet.Route>> kits = new java.util.LinkedHashMap<>();
        List<Map.Entry<CompiledSheet.Source, List<CompiledSheet.Route>>> plain = new ArrayList<>();
        for (CompiledSheet.Source source : sheet.sources()) {
            if (source.resource() != ResourceKind.ITEM || !ctx.runtime().active(source)) {
                continue;
            }
            List<CompiledSheet.Route> routes = ctx.runtime().routes(source);
            if (routes.isEmpty()) {
                continue;
            }
            List<CompiledSheet.Route> direct = new ArrayList<>();
            for (CompiledSheet.Route route : routes) {
                if (route.kit() == null) {
                    direct.add(route);
                } else {
                    kits.computeIfAbsent(route.kit().id(), k -> new ArrayList<>()).add(route);
                }
            }
            if (!direct.isEmpty()) {
                plain.add(Map.entry(source, direct));
            }
        }
        // Sheets with a higher priority move first, kits included: each kit runs before the sources of its sheet's
        // priority and below (sharing a chest with a plain source of the same priority, it gets its parts first).
        List<Map.Entry<UUID, List<CompiledSheet.Route>>> byPriority = new ArrayList<>(kits.entrySet());
        byPriority.sort((a, b) -> Integer.compare(sheet.priorityOf(b.getKey()), sheet.priorityOf(a.getKey())));
        int nextKit = 0;
        for (Map.Entry<CompiledSheet.Source, List<CompiledSheet.Route>> entry : plain) {
            int priority = sheet.priorityOf(entry.getKey().node().id());
            while (nextKit < byPriority.size() && sheet.priorityOf(byPriority.get(nextKit).getKey()) >= priority) {
                runKit(ctx, sheet, byPriority.get(nextKit++));
            }
            runSource(ctx, sheet, entry.getKey(), entry.getValue());
        }
        while (nextKit < byPriority.size()) {
            runKit(ctx, sheet, byPriority.get(nextKit++));
        }
    }

    private static void runSource(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, List<CompiledSheet.Route> direct) {
        int interval = source.settings().interval() > 0 ? source.settings().interval() : sheet.intervalOf(source.node().id());
        UUID id = source.node().id();
        if (due(ctx, id, (long) interval * ctx.runtime().backoff(id)) || ctx.runtime().forced(sheet, direct)) {
            if (!ctx.runtime().canAffordOperation()) {
                ctx.runtime().status(sheet.owner(id), Status.NO_POWER);
                return;
            }
            boolean moved = operate(ctx, sheet, source, ctx.runtime().ordered(direct)) > 0;
            if (moved) {
                ctx.runtime().chargeOperation();
            }
            ctx.runtime().recordOperation(id, moved);
        }
    }

    private static void runKit(NetworkRuntime.Context ctx, CompiledSheet sheet, Map.Entry<UUID, List<CompiledSheet.Route>> kit) {
        UUID id = kit.getKey();
        if (due(ctx, id, (long) sheet.intervalOf(id) * ctx.runtime().backoff(id)) || ctx.runtime().forced(sheet, kit.getValue())) {
            if (!ctx.runtime().canAffordOperation()) {
                ctx.runtime().status(sheet.owner(id), Status.NO_POWER);
                return;
            }
            boolean moved = KitMover.operate(ctx, sheet, kit.getValue().getFirst().kit(), ctx.runtime().ordered(kit.getValue()));
            if (moved) {
                ctx.runtime().chargeOperation();
            }
            ctx.runtime().recordOperation(id, moved);
        }
    }

    /** Spreads nodes with the same interval over different ticks. */
    private static boolean due(NetworkRuntime.Context ctx, UUID node, long interval) {
        return Math.floorMod(ctx.runtime().ticks() + node.hashCode(), Math.max(1, interval)) == 0;
    }

    /** Returns how many items moved. */
    private static long operate(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, List<CompiledSheet.Route> routes) {
        UUID owner = sheet.owner(source.node().id());
        ItemPort from = ctx.runtime().itemPort(ctx.server(), ctx.network(), source.settings().port());
        if (from.isEmpty()) {
            ctx.runtime().status(owner, Status.DEVICE_OFFLINE);
            return 0;
        }
        Status why = Status.SOURCE_EMPTY;
        ServerLevel level = ctx.server().getLevel(source.settings().port().device().dimension());
        Map<UUID, ItemPort> sinks = new HashMap<>();
        // 0 (or less) means one full stack of whatever moves first, like XNet's "stack" mode.
        boolean oneStack = source.settings().perOperation() <= 0;
        long budget = oneStack ? Long.MAX_VALUE : source.settings().perOperation();
        long total = 0;
        Filters.Context plain = Filters.Context.of(level);

        // Round robin and weighted splitters turn after each delivery: the next slot goes by the turned order, so an
        // operation moving several stacks spreads them as the splitters say (not all down the first route).
        List<CompiledSheet.Route> order = routes;
        boolean turned = false;
        for (IItemHandler handler : from.handlers) {
            for (int slot = 0; slot < handler.getSlots() && budget > 0; slot++) {
                if (turned) {
                    order = ctx.runtime().ordered(routes);
                    turned = false;
                }
                if (!from.inRange(slot)) {
                    continue;
                }
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                long limit = oneStack && total == 0 ? stack.getMaxStackSize() : budget;
                long available = Math.min(Math.min(stack.getCount(), limit),
                        from.limit(source.settings().keep(), sheet, stack, plain, true));
                if (available <= 0) {
                    why = Status.furthest(why, Status.KEEP_RULE);
                }
                for (CompiledSheet.Route route : order) {
                    if (available <= 0) {
                        break;
                    }
                    ItemPort to = sinks.computeIfAbsent(route.sink().id(),
                            id -> ctx.runtime().itemPort(ctx.server(), ctx.network(), route.sinkSettings().port()));
                    if (to.isEmpty()) {
                        why = Status.furthest(why, Status.TARGET_OFFLINE);
                        continue;
                    }
                    Filters.Context context = new Filters.Context(level, to::contains, null);
                    if (!passes(route.gates(), stack, context)) {
                        why = Status.furthest(why, Status.FILTERED_OUT);
                        continue;
                    }
                    long rate = ctx.runtime().available(route.rateLimits());
                    long room = to.limit(route.sinkSettings().caps(), sheet, stack, context, false);
                    long amount = Math.min(available, Math.min(rate, room));
                    if (amount <= 0) {
                        why = Status.furthest(why, room <= 0 ? Status.CAP_REACHED : Status.RATE_LIMITED);
                        continue;
                    }
                    int stackSize = stack.getMaxStackSize();
                    String what = "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                    int moved = transfer(ctx, handler, slot, (int) Math.min(amount, Integer.MAX_VALUE), to);
                    if (moved <= 0) {
                        why = Status.furthest(why, Status.TARGET_FULL);
                    }
                    if (moved > 0) {
                        if (oneStack && total == 0) {
                            budget = stackSize;
                        }
                        total += moved;
                        available -= moved;
                        budget -= moved;
                        ctx.runtime().delivered(route, moved, what);
                        ctx.runtime().advance(route);
                        turned = true;
                        stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        }
        ctx.runtime().status(owner, total > 0 ? null : why);
        return total;
    }

    static boolean passes(List<CompiledSheet.Gate> gates, ItemStack stack, Filters.Context context) {
        for (CompiledSheet.Gate gate : gates) {
            if (gate.items().test(stack, context) == gate.reject()) {
                return false;
            }
        }
        return true;
    }

    /** Moves up to {@code amount} from one slot into the sink. Returns how many arrived. */
    static int transfer(NetworkRuntime.Context ctx, IItemHandler handler, int slot, int amount, ItemPort to) {
        ItemStack simulated = handler.extractItem(slot, amount, true);
        if (simulated.isEmpty()) {
            return 0;
        }
        int accepted = simulated.getCount() - to.insert(simulated, true).getCount();
        if (accepted <= 0) {
            return 0;
        }
        ItemStack extracted = handler.extractItem(slot, accepted, false);
        if (extracted.isEmpty()) {
            return 0;
        }
        ItemStack refused = to.insert(extracted, false);
        if (!refused.isEmpty()) {
            giveBack(ctx, handler, slot, refused);
        }
        return extracted.getCount() - refused.getCount();
    }

    /** Returns items to where they came from; anything the source refuses goes to the overflow buffer. */
    static void giveBack(NetworkRuntime.Context ctx, IItemHandler handler, int slot, ItemStack stack) {
        ItemStack left = handler.insertItem(slot, stack, false);
        if (!left.isEmpty()) {
            left = ItemHandlerHelper.insertItemStacked(handler, left, false);
        }
        if (!left.isEmpty()) {
            Overflow.add(ctx, left);
        }
    }
}
