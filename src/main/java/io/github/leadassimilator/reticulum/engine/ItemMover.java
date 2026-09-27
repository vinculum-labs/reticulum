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
        if (Overflow.itemsFull(ctx.network())) {
            return;
        }
        // Kits can gather their parts from several sources, so kit routes are grouped per kit across the sheet.
        Map<UUID, List<CompiledSheet.Route>> kits = new HashMap<>();
        for (CompiledSheet.Source source : sheet.sources()) {
            if (source.resource() != ResourceKind.ITEM || source.routes().isEmpty()) {
                continue;
            }
            List<CompiledSheet.Route> direct = new ArrayList<>();
            for (CompiledSheet.Route route : source.routes()) {
                if (route.kit() == null) {
                    direct.add(route);
                } else {
                    kits.computeIfAbsent(route.kit().id(), k -> new ArrayList<>()).add(route);
                }
            }
            int interval = source.settings().interval() > 0 ? source.settings().interval() : sheet.sheet().interval();
            UUID id = source.node().id();
            if (!direct.isEmpty() && due(ctx, id, interval * ctx.runtime().backoff(id))) {
                ctx.runtime().recordOperation(id, operate(ctx, sheet, source, ctx.runtime().ordered(direct)) > 0);
            }
        }
        for (Map.Entry<UUID, List<CompiledSheet.Route>> kit : kits.entrySet()) {
            UUID id = kit.getKey();
            if (due(ctx, id, sheet.sheet().interval() * ctx.runtime().backoff(id))) {
                ctx.runtime().recordOperation(id, KitMover.operate(ctx, sheet, kit.getValue().getFirst().kit(), ctx.runtime().ordered(kit.getValue())));
            }
        }
    }

    /** Spreads nodes with the same interval over different ticks. */
    private static boolean due(NetworkRuntime.Context ctx, UUID node, int interval) {
        return Math.floorMod(ctx.runtime().ticks() + node.hashCode(), interval) == 0;
    }

    /** Returns how many items moved. */
    private static long operate(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, List<CompiledSheet.Route> routes) {
        ItemPort from = new ItemPort(ctx.runtime().items(ctx.server(), ctx.network(), source.settings().port()), source.settings().port().slots());
        if (from.isEmpty()) {
            return 0;
        }
        ServerLevel level = ctx.server().getLevel(source.settings().port().device().dimension());
        Map<UUID, ItemPort> sinks = new HashMap<>();
        long perOperation = Math.max(1, source.settings().perOperation());
        long budget = perOperation;
        Filters.Context plain = Filters.Context.of(level);

        for (IItemHandler handler : from.handlers) {
            for (int slot = 0; slot < handler.getSlots() && budget > 0; slot++) {
                if (!from.inRange(slot)) {
                    continue;
                }
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                long available = Math.min(Math.min(stack.getCount(), budget),
                        from.limit(source.settings().keep(), sheet, stack, plain, true));
                for (CompiledSheet.Route route : routes) {
                    if (available <= 0) {
                        break;
                    }
                    ItemPort to = sinks.computeIfAbsent(route.sink().id(),
                            id -> new ItemPort(ctx.runtime().items(ctx.server(), ctx.network(), route.sinkSettings().port()),
                                    route.sinkSettings().port().slots()));
                    if (to.isEmpty()) {
                        continue;
                    }
                    Filters.Context context = new Filters.Context(level, to::contains, null);
                    if (!passes(route.gates(), stack, context)) {
                        continue;
                    }
                    long amount = Math.min(available, ctx.runtime().available(route.rateLimits()));
                    amount = Math.min(amount, to.limit(route.sinkSettings().caps(), sheet, stack, context, false));
                    if (amount <= 0) {
                        continue;
                    }
                    int moved = transfer(ctx, handler, slot, (int) Math.min(amount, Integer.MAX_VALUE), to);
                    if (moved > 0) {
                        available -= moved;
                        budget -= moved;
                        ctx.runtime().delivered(route, moved);
                        ctx.runtime().advance(route);
                        stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        }
        return perOperation - budget;
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
