package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.integration.Mods;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Energy, fluid and chemical flows, which behave like Mekanism pressurized tubes: every tick, each sink says how much it can
 * take right now, the source supplies what it can, and the supply is shared evenly (a sink needing less than its
 * share gives the rest to the others). A priority splitter anywhere on the routes switches to filling in order.
 */
final class ContinuousMover {
    private ContinuousMover() {
    }

    static void run(NetworkRuntime.Context ctx, CompiledSheet sheet) {
        for (CompiledSheet.Source source : sheet.sources()) {
            if (!ctx.runtime().active(source) || ctx.runtime().routes(source).isEmpty()) {
                continue;
            }
            // Without power only energy moves, so the network can still recover.
            if (!ctx.runtime().powered() && source.resource() != ResourceKind.ENERGY) {
                ctx.runtime().status(sheet.owner(source.node().id()), Status.NO_POWER);
                continue;
            }
            if (source.resource() == ResourceKind.ENERGY) {
                energy(ctx, sheet, source);
            } else if (source.resource() == ResourceKind.FLUID) {
                if (Overflow.fluidsFull(ctx.network())) {
                    ctx.runtime().status(sheet.owner(source.node().id()), Status.OVERFLOW_FULL);
                } else {
                    fluid(ctx, sheet, source);
                }
            } else if (source.resource() == ResourceKind.CHEMICAL && Mods.MEKANISM) {
                ChemicalMover.run(ctx, sheet, source);
            }
        }
    }

    // ---------------------------------------------------------------- energy

    private static void energy(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source) {
        UUID owner = sheet.owner(source.node().id());
        List<IEnergyStorage> from = ctx.runtime().energy(ctx.server(), ctx.network(), source.settings().port());
        if (from.isEmpty()) {
            ctx.runtime().status(owner, Status.DEVICE_OFFLINE);
            return;
        }
        long supply = 0;
        long stored = 0;
        for (IEnergyStorage storage : from) {
            supply += storage.extractEnergy(Integer.MAX_VALUE, true);
            stored += storage.getEnergyStored();
        }
        long keep = keepAmount(source.settings().keep());
        if (keep > 0) {
            supply = Math.min(supply, Math.max(0, stored - keep));
        }
        if (supply <= 0) {
            ctx.runtime().status(owner, keep > 0 && stored > 0 ? Status.KEEP_RULE : Status.SOURCE_EMPTY);
            return;
        }
        List<CompiledSheet.Route> routes = ctx.runtime().ordered(ctx.runtime().routes(source));
        List<List<IEnergyStorage>> sinks = new ArrayList<>();
        long[] demand = new long[routes.size()];
        for (int i = 0; i < routes.size(); i++) {
            CompiledSheet.Route route = routes.get(i);
            List<IEnergyStorage> to = ctx.runtime().energy(ctx.server(), ctx.network(), route.sinkSettings().port());
            sinks.add(to);
            long max = Math.min(ReticulumConfig.ENERGY_PER_TICK.getAsInt(), ctx.runtime().available(route.rateLimits()));
            long sinkStored = 0;
            for (IEnergyStorage storage : to) {
                sinkStored += storage.getEnergyStored();
            }
            max = Math.min(max, capRoom(route.sinkSettings().caps(), sinkStored));
            for (IEnergyStorage storage : to) {
                if (max <= 0) {
                    break;
                }
                int accepted = storage.receiveEnergy((int) Math.min(max, Integer.MAX_VALUE), true);
                demand[i] += accepted;
                max -= accepted;
            }
        }
        long[] share = share(supply, demand, anyPriority(routes), weights(routes));
        long total = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (share[i] <= 0) {
                continue;
            }
            long moved = moveEnergy(from, sinks.get(i), share[i]);
            if (moved > 0) {
                total += moved;
                ctx.runtime().delivered(routes.get(i), moved);
                ctx.runtime().advance(routes.get(i));
            }
        }
        ctx.runtime().status(owner, total > 0 ? null : Status.TARGET_FULL);
    }

    private static long moveEnergy(List<IEnergyStorage> from, List<IEnergyStorage> to, long amount) {
        long taken = 0;
        for (IEnergyStorage storage : from) {
            if (taken >= amount) {
                break;
            }
            taken += storage.extractEnergy((int) Math.min(amount - taken, Integer.MAX_VALUE), false);
        }
        long left = taken;
        for (IEnergyStorage storage : to) {
            if (left <= 0) {
                break;
            }
            left -= storage.receiveEnergy((int) Math.min(left, Integer.MAX_VALUE), false);
        }
        // Energy the sink refused after all goes back where it came from; FE is never held in transit.
        for (IEnergyStorage storage : from) {
            if (left <= 0) {
                break;
            }
            left -= storage.receiveEnergy((int) Math.min(left, Integer.MAX_VALUE), false);
        }
        return taken - Math.max(0, left);
    }

    /** Energy has no types, so every keep rule counts the total stored. */
    private static long keepAmount(List<NodeSettings.Limit> keep) {
        long amount = 0;
        for (NodeSettings.Limit limit : keep) {
            amount = Math.max(amount, limit.amount());
        }
        return amount;
    }

    private static long capRoom(List<NodeSettings.Limit> caps, long stored) {
        long room = Long.MAX_VALUE;
        for (NodeSettings.Limit cap : caps) {
            room = Math.min(room, Math.max(0, cap.amount() - stored));
        }
        return room;
    }

    // ---------------------------------------------------------------- fluids

    private static void fluid(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source) {
        UUID owner = sheet.owner(source.node().id());
        List<IFluidHandler> from = ctx.runtime().fluids(ctx.server(), ctx.network(), source.settings().port());
        if (from.isEmpty()) {
            ctx.runtime().status(owner, Status.DEVICE_OFFLINE);
            return;
        }
        ServerLevel level = ctx.server().getLevel(source.settings().port().device().dimension());
        List<FluidStack> kinds = new ArrayList<>();
        for (IFluidHandler handler : from) {
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack fluid = handler.getFluidInTank(tank);
                if (!fluid.isEmpty() && kinds.stream().noneMatch(k -> FluidStack.isSameFluidSameComponents(k, fluid))) {
                    kinds.add(fluid.copyWithAmount(1));
                }
            }
        }
        List<CompiledSheet.Route> ordered = ctx.runtime().ordered(ctx.runtime().routes(source));
        long total = 0;
        Status why = Status.SOURCE_EMPTY;
        for (FluidStack kind : kinds) {
            long moved = fluidKind(ctx, sheet, source, level, from, kind, ordered);
            total += moved;
            if (moved == 0) {
                why = Status.furthest(why, Status.TARGET_FULL);
            }
        }
        ctx.runtime().status(owner, total > 0 ? null : why);
    }

    /** Returns how much moved. */
    private static long fluidKind(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, ServerLevel level,
            List<IFluidHandler> from, FluidStack kind, List<CompiledSheet.Route> ordered) {
        Filters.Context plain = Filters.Context.of(level);
        long supply = 0;
        for (IFluidHandler handler : from) {
            supply += handler.drain(kind.copyWithAmount(Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE).getAmount();
        }
        supply = Math.min(supply, fluidLimit(sheet, source.settings().keep(), from, kind, plain, true));
        if (supply <= 0) {
            return 0;
        }
        List<CompiledSheet.Route> routes = new ArrayList<>();
        List<List<IFluidHandler>> sinks = new ArrayList<>();
        List<Long> demands = new ArrayList<>();
        for (CompiledSheet.Route route : ordered) {
            List<IFluidHandler> to = ctx.runtime().fluids(ctx.server(), ctx.network(), route.sinkSettings().port());
            if (to.isEmpty()) {
                continue;
            }
            Filters.Context context = new Filters.Context(level, null, f -> contains(to, f));
            if (!passes(route.gates(), kind, context)) {
                continue;
            }
            long max = Math.min(ReticulumConfig.FLUID_PER_TICK.getAsInt(), ctx.runtime().available(route.rateLimits()));
            max = Math.min(max, fluidLimit(sheet, route.sinkSettings().caps(), to, kind, context, false));
            long demand = 0;
            for (IFluidHandler handler : to) {
                if (max - demand <= 0) {
                    break;
                }
                demand += handler.fill(kind.copyWithAmount((int) Math.min(max - demand, Integer.MAX_VALUE)), IFluidHandler.FluidAction.SIMULATE);
            }
            routes.add(route);
            sinks.add(to);
            demands.add(demand);
        }
        long[] share = share(supply, demands.stream().mapToLong(Long::longValue).toArray(), anyPriority(routes), weights(routes));
        long total = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (share[i] <= 0) {
                continue;
            }
            long moved = moveFluid(ctx, from, sinks.get(i), kind, share[i]);
            if (moved > 0) {
                total += moved;
                ctx.runtime().delivered(routes.get(i), moved);
                ctx.runtime().advance(routes.get(i));
            }
        }
        return total;
    }

    private static long moveFluid(NetworkRuntime.Context ctx, List<IFluidHandler> from, List<IFluidHandler> to, FluidStack kind, long amount) {
        List<FluidStack> drained = new ArrayList<>();
        long taken = 0;
        for (IFluidHandler handler : from) {
            if (taken >= amount) {
                break;
            }
            FluidStack got = handler.drain(kind.copyWithAmount((int) Math.min(amount - taken, Integer.MAX_VALUE)), IFluidHandler.FluidAction.EXECUTE);
            if (!got.isEmpty()) {
                drained.add(got);
                taken += got.getAmount();
            }
        }
        long delivered = 0;
        for (FluidStack stack : drained) {
            FluidStack left = stack.copy();
            for (IFluidHandler handler : to) {
                if (left.isEmpty()) {
                    break;
                }
                left.shrink(handler.fill(left.copy(), IFluidHandler.FluidAction.EXECUTE));
            }
            delivered += stack.getAmount() - left.getAmount();
            for (IFluidHandler handler : from) {
                if (left.isEmpty()) {
                    break;
                }
                left.shrink(handler.fill(left.copy(), IFluidHandler.FluidAction.EXECUTE));
            }
            if (!left.isEmpty()) {
                Overflow.add(ctx, left);
            }
        }
        return delivered;
    }

    static boolean passes(List<CompiledSheet.Gate> gates, FluidStack stack, Filters.Context context) {
        for (CompiledSheet.Gate gate : gates) {
            if (gate.fluids().test(stack, context) == gate.reject()) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<IFluidHandler> handlers, FluidStack like) {
        for (IFluidHandler handler : handlers) {
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                if (FluidStack.isSameFluidSameComponents(handler.getFluidInTank(tank), like)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Room left by keep rules (source) or caps (sink) for one fluid kind, in mB. */
    private static long fluidLimit(CompiledSheet sheet, List<NodeSettings.Limit> limits, List<IFluidHandler> handlers, FluidStack kind,
            Filters.Context context, boolean keep) {
        long allowed = Long.MAX_VALUE;
        for (NodeSettings.Limit limit : limits) {
            Filters.FluidFilter filter = sheet.fluidFilter(limit);
            if (!filter.test(kind, context)) {
                continue;
            }
            long present = 0;
            for (IFluidHandler handler : handlers) {
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack fluid = handler.getFluidInTank(tank);
                    boolean counted = limit.scope() == NodeSettings.Scope.PER_TYPE
                            ? FluidStack.isSameFluidSameComponents(fluid, kind)
                            : !fluid.isEmpty() && filter.test(fluid, context);
                    if (counted) {
                        present += fluid.getAmount();
                    }
                }
            }
            allowed = Math.min(allowed, Math.max(0, keep ? present - limit.amount() : limit.amount() - present));
        }
        return allowed;
    }

    // ---------------------------------------------------------------- sharing

    /** Filling in order: a priority or nearest-first splitter anywhere on the routes (they arrive already in that order). */
    static boolean anyPriority(List<CompiledSheet.Route> routes) {
        for (CompiledSheet.Route route : routes) {
            for (CompiledSheet.Branch branch : route.branches()) {
                if (branch.mode() == NodeSettings.SplitMode.PRIORITY || branch.mode() == NodeSettings.SplitMode.NEAREST) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Each route's weight: the product of its weighted splitters' output weights (1 without any). */
    static long[] weights(List<CompiledSheet.Route> routes) {
        long[] weights = new long[routes.size()];
        for (int i = 0; i < weights.length; i++) {
            long weight = 1;
            for (CompiledSheet.Branch branch : routes.get(i).branches()) {
                if (branch.mode() == NodeSettings.SplitMode.WEIGHTED) {
                    weight *= branch.weight();
                }
            }
            weights[i] = weight;
        }
        return weights;
    }

    /**
     * Splits {@code supply} over the demands. In order when {@code inOrder}; otherwise evenly, with any remainder
     * handed out one unit at a time and unused shares passed on to sinks that want more.
     */
    static long[] share(long supply, long[] demand, boolean inOrder) {
        long[] even = new long[demand.length];
        java.util.Arrays.fill(even, 1);
        return share(supply, demand, inOrder, even);
    }

    /**
     * Splits {@code supply} over the demands: in order when {@code inOrder}; otherwise in proportion to {@code weights}
     * (all equal: evenly), with any remainder handed out one unit at a time and unused shares passed on to sinks that
     * want more. A weight of 0 gets nothing.
     */
    static long[] share(long supply, long[] demand, boolean inOrder, long[] weights) {
        long[] share = new long[demand.length];
        if (inOrder) {
            for (int i = 0; i < demand.length && supply > 0; i++) {
                share[i] = Math.min(demand[i], supply);
                supply -= share[i];
            }
            return share;
        }
        while (supply > 0) {
            long hungryWeight = 0;
            for (int i = 0; i < demand.length; i++) {
                if (share[i] < demand[i] && weights[i] > 0) {
                    hungryWeight += weights[i];
                }
            }
            if (hungryWeight == 0) {
                break;
            }
            long given = 0;
            long left = supply;
            for (int i = 0; i < demand.length; i++) {
                if (share[i] >= demand[i] || weights[i] <= 0) {
                    continue;
                }
                long portion = (long) Math.floor((double) supply * weights[i] / hungryWeight);
                long add = Math.min(portion, demand[i] - share[i]);
                share[i] += add;
                given += add;
                left -= add;
            }
            // What rounding left over goes out one unit at a time, in order.
            for (int i = 0; i < demand.length && left > 0 && given < supply; i++) {
                if (share[i] < demand[i] && weights[i] > 0) {
                    share[i]++;
                    given++;
                    left--;
                }
            }
            if (given == 0) {
                break;
            }
            supply -= given;
        }
        return share;
    }
}
