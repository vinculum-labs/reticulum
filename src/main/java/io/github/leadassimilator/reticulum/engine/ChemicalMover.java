package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.integration.mekanism.MekanismChemicals;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Chemical flows, the same way as fluids: every tick, demand-driven, shared evenly. Each chemical in the source is
 * moved on its own, so a sink tank only ever gets the chemical it holds (Mekanism's handlers refuse anything else)
 * and mixed chemicals never block each other. Only used when Mekanism is installed.
 */
final class ChemicalMover {
    private ChemicalMover() {
    }

    static void run(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source) {
        UUID owner = sheet.owner(source.node().id());
        if (full(ctx.network())) {
            ctx.runtime().status(owner, Status.OVERFLOW_FULL);
            return;
        }
        List<IChemicalHandler> from = handlers(ctx, source.settings().port());
        if (from.isEmpty()) {
            ctx.runtime().status(owner, Status.DEVICE_OFFLINE);
            return;
        }
        ServerLevel level = ctx.server().getLevel(source.settings().port().device().dimension());
        List<ChemicalStack> kinds = new ArrayList<>();
        for (IChemicalHandler handler : from) {
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack chemical = handler.getChemicalInTank(tank);
                if (!chemical.isEmpty() && kinds.stream().noneMatch(k -> ChemicalStack.isSameChemical(k, chemical))) {
                    kinds.add(chemical.copyWithAmount(1));
                }
            }
        }
        List<CompiledSheet.Route> ordered = ctx.runtime().ordered(ctx.runtime().routes(source));
        long total = 0;
        Status why = Status.SOURCE_EMPTY;
        for (ChemicalStack kind : kinds) {
            long moved = kind(ctx, sheet, source, level, from, kind, ordered);
            total += moved;
            if (moved == 0) {
                why = Status.furthest(why, Status.TARGET_FULL);
            }
        }
        ctx.runtime().status(owner, total > 0 ? null : why);
    }

    private static List<IChemicalHandler> handlers(NetworkRuntime.Context ctx, NodeSettings.Port port) {
        return ctx.runtime().handlers(ctx.server(), ctx.network(), port, MekanismChemicals.CAPABILITY, ResourceKind.CHEMICAL);
    }

    /** Returns how much moved. */
    private static long kind(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, ServerLevel level,
            List<IChemicalHandler> from, ChemicalStack kind, List<CompiledSheet.Route> ordered) {
        long supply = 0;
        for (IChemicalHandler handler : from) {
            supply += handler.extractChemical(kind.copyWithAmount(Long.MAX_VALUE), Action.SIMULATE).getAmount();
        }
        supply = Math.min(supply, limit(sheet, source.settings().keep(), from, kind, Filters.Context.of(level), true));
        if (supply <= 0) {
            return 0;
        }
        List<CompiledSheet.Route> routes = new ArrayList<>();
        List<List<IChemicalHandler>> sinks = new ArrayList<>();
        List<Long> demands = new ArrayList<>();
        for (CompiledSheet.Route route : ordered) {
            List<IChemicalHandler> to = handlers(ctx, route.sinkSettings().port());
            if (to.isEmpty()) {
                continue;
            }
            Filters.Context context = new Filters.Context(level, null, null, c -> contains(to, (ChemicalStack) c));
            if (!passes(route.gates(), kind, context)) {
                continue;
            }
            long max = Math.min(ReticulumConfig.CHEMICAL_PER_TICK.getAsLong(), ctx.runtime().available(route.rateLimits()));
            max = Math.min(max, limit(sheet, route.sinkSettings().caps(), to, kind, context, false));
            long demand = 0;
            for (IChemicalHandler handler : to) {
                if (max - demand <= 0) {
                    break;
                }
                long offered = max - demand;
                demand += offered - handler.insertChemical(kind.copyWithAmount(offered), Action.SIMULATE).getAmount();
            }
            routes.add(route);
            sinks.add(to);
            demands.add(demand);
        }
        long[] share = ContinuousMover.share(supply, demands.stream().mapToLong(Long::longValue).toArray(), ContinuousMover.anyPriority(routes),
                ContinuousMover.weights(routes));
        long total = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (share[i] <= 0) {
                continue;
            }
            long moved = move(ctx, from, sinks.get(i), kind, share[i]);
            if (moved > 0) {
                total += moved;
                ctx.runtime().delivered(routes.get(i), moved);
                ctx.runtime().advance(routes.get(i));
            }
        }
        return total;
    }

    private static long move(NetworkRuntime.Context ctx, List<IChemicalHandler> from, List<IChemicalHandler> to, ChemicalStack kind, long amount) {
        long taken = 0;
        for (IChemicalHandler handler : from) {
            if (taken >= amount) {
                break;
            }
            taken += handler.extractChemical(kind.copyWithAmount(amount - taken), Action.EXECUTE).getAmount();
        }
        ChemicalStack left = kind.copyWithAmount(taken);
        for (IChemicalHandler handler : to) {
            if (left.isEmpty()) {
                break;
            }
            left = handler.insertChemical(left, Action.EXECUTE);
        }
        long delivered = taken - left.getAmount();
        // What the sink refused after all goes back to the source, and failing that to the overflow buffer.
        for (IChemicalHandler handler : from) {
            if (left.isEmpty()) {
                break;
            }
            left = handler.insertChemical(left, Action.EXECUTE);
        }
        if (!left.isEmpty()) {
            ctx.network().overflow().chemicals().merge(MekanismChemicals.id(left), left.getAmount(), Long::sum);
            NetworkManager.changed(ctx.server(), ctx.network());
        }
        return delivered;
    }

    private static boolean passes(List<CompiledSheet.Gate> gates, ChemicalStack stack, Filters.Context context) {
        for (CompiledSheet.Gate gate : gates) {
            if (gate.chemicals().test(stack, context) == gate.reject()) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<IChemicalHandler> handlers, ChemicalStack like) {
        for (IChemicalHandler handler : handlers) {
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                if (ChemicalStack.isSameChemical(handler.getChemicalInTank(tank), like)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Room left by keep rules (source) or caps (sink) for one chemical, in mB. */
    private static long limit(CompiledSheet sheet, List<NodeSettings.Limit> limits, List<IChemicalHandler> handlers, ChemicalStack kind,
            Filters.Context context, boolean keep) {
        long allowed = Long.MAX_VALUE;
        for (NodeSettings.Limit limit : limits) {
            Filters.ChemicalFilter filter = sheet.chemicalFilter(limit);
            if (!filter.test(kind, context)) {
                continue;
            }
            long present = 0;
            for (IChemicalHandler handler : handlers) {
                for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                    ChemicalStack chemical = handler.getChemicalInTank(tank);
                    boolean counted = limit.scope() == NodeSettings.Scope.PER_TYPE
                            ? ChemicalStack.isSameChemical(chemical, kind)
                            : !chemical.isEmpty() && filter.test(chemical, context);
                    if (counted) {
                        present += chemical.getAmount();
                    }
                }
            }
            allowed = Math.min(allowed, Math.max(0, keep ? present - limit.amount() : limit.amount() - present));
        }
        return allowed;
    }

    /** mB of matching chemicals a port holds, for level sensors. */
    static long count(NetworkRuntime.Context ctx, NodeSettings.Port port, Filters.ChemicalFilter filter, Filters.Context context) {
        List<IChemicalHandler> handlers = ctx.runtime().view(ctx.server(), ctx.network(), port, MekanismChemicals.CAPABILITY, ResourceKind.CHEMICAL);
        long total = 0;
        for (IChemicalHandler handler : handlers) {
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack chemical = handler.getChemicalInTank(tank);
                if (!chemical.isEmpty() && filter.test(chemical, context)) {
                    total += chemical.getAmount();
                }
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- overflow

    static boolean full(Network network) {
        long limit = ReticulumConfig.OVERFLOW_CHEMICAL_BUCKETS.getAsInt() * 1000L;
        return network.overflow().chemicals().values().stream().anyMatch(amount -> amount >= limit);
    }

    /** Tries to deliver buffered chemicals along one route. */
    static boolean retry(NetworkRuntime.Context ctx, CompiledSheet.Route route, ServerLevel level) {
        List<IChemicalHandler> to = handlers(ctx, route.sinkSettings().port());
        boolean changed = false;
        Filters.Context context = Filters.Context.of(level);
        Iterator<Map.Entry<ResourceLocation, Long>> it = ctx.network().overflow().chemicals().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, Long> held = it.next();
            ChemicalStack left = MekanismChemicals.stack(held.getKey(), held.getValue());
            if (left == null || !passes(route.gates(), left, context)) {
                continue;
            }
            for (IChemicalHandler handler : to) {
                left = handler.insertChemical(left, Action.EXECUTE);
            }
            if (left.getAmount() != held.getValue()) {
                changed = true;
                if (left.isEmpty()) {
                    it.remove();
                } else {
                    held.setValue(left.getAmount());
                }
            }
        }
        return changed;
    }
}
