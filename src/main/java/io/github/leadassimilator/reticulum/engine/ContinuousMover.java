package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Energy, fluid and chemical flows, which behave like Mekanism pressurized tubes (see {@link TankFlow}), and the
 * sharing they use: each sink says how much it can take, the source supplies what it can, and the supply is shared
 * evenly (a sink needing less than its share gives the rest to the others). A priority splitter anywhere on the
 * routes switches to filling in order.
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
            switch (source.resource()) {
                case ENERGY -> TankFlow.run(ctx, sheet, source, ENERGY);
                case FLUID -> TankFlow.run(ctx, sheet, source, FLUIDS);
                case CHEMICAL -> {
                    if (Mods.MEKANISM) {
                        ChemicalMover.run(ctx, sheet, source);
                    }
                }
                default -> {
                }
            }
        }
    }

    // ---------------------------------------------------------------- energy

    /** Energy has one kind; every keep rule and cap counts all of it. */
    enum Energy {
        FE
    }

    static final TankFlow.Tank<IEnergyStorage, Energy> ENERGY = new TankFlow.Tank<>() {
        @Override
        public List<IEnergyStorage> handlers(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().energy(ctx.server(), ctx.network(), port);
        }

        @Override
        public List<IEnergyStorage> view(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().energyView(ctx.server(), ctx.network(), port);
        }

        @Override
        public List<Energy> kinds(List<IEnergyStorage> handlers) {
            return List.of(Energy.FE);
        }

        @Override
        public long extract(IEnergyStorage handler, Energy kind, long max, boolean simulate) {
            return handler.extractEnergy((int) Math.min(max, Integer.MAX_VALUE), simulate);
        }

        @Override
        public long insert(IEnergyStorage handler, Energy kind, long amount, boolean simulate) {
            return handler.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), simulate);
        }

        @Override
        public void forEachStored(List<IEnergyStorage> handlers, TankFlow.StoredConsumer<Energy> action) {
            for (IEnergyStorage storage : handlers) {
                if (storage.getEnergyStored() > 0) {
                    action.accept(Energy.FE, storage.getEnergyStored());
                }
            }
        }

        @Override
        public boolean sameKind(Energy a, Energy b) {
            return true;
        }

        @Override
        public boolean passes(CompiledSheet.Gate gate, Energy kind, Filters.Context context) {
            return true;
        }

        @Override
        public boolean matches(CompiledSheet sheet, NodeSettings.Limit limit, Energy kind, Filters.Context context) {
            return true;
        }

        @Override
        public Filters.Context context(ServerLevel level, List<IEnergyStorage> sinkView) {
            return Filters.Context.of(level);
        }

        @Override
        public long perTick() {
            return ReticulumConfig.ENERGY_PER_TICK.getAsInt();
        }

        @Override
        public boolean overflowFull(Network network) {
            return false;
        }

        @Override
        public void overflow(NetworkRuntime.Context ctx, Energy kind, long amount) {
            // Only when a sink takes less than it said it would and the source takes none back.
            NetworkRuntime.catchLostEnergy(ctx.server(), ctx.network(), amount);
        }
    };

    // ---------------------------------------------------------------- fluids

    static final TankFlow.Tank<IFluidHandler, FluidStack> FLUIDS = new TankFlow.Tank<>() {
        @Override
        public List<IFluidHandler> handlers(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().fluids(ctx.server(), ctx.network(), port);
        }

        @Override
        public List<IFluidHandler> view(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().fluidView(ctx.server(), ctx.network(), port);
        }

        @Override
        public List<FluidStack> kinds(List<IFluidHandler> handlers) {
            List<FluidStack> kinds = new ArrayList<>();
            forEachStored(handlers, (fluid, amount) -> {
                if (kinds.stream().noneMatch(k -> FluidStack.isSameFluidSameComponents(k, fluid))) {
                    kinds.add(fluid.copyWithAmount(1));
                }
            });
            return kinds;
        }

        @Override
        public long extract(IFluidHandler handler, FluidStack kind, long max, boolean simulate) {
            return handler.drain(kind.copyWithAmount((int) Math.min(max, Integer.MAX_VALUE)), action(simulate)).getAmount();
        }

        @Override
        public long insert(IFluidHandler handler, FluidStack kind, long amount, boolean simulate) {
            return handler.fill(kind.copyWithAmount((int) Math.min(amount, Integer.MAX_VALUE)), action(simulate));
        }

        @Override
        public void forEachStored(List<IFluidHandler> handlers, TankFlow.StoredConsumer<FluidStack> action) {
            for (IFluidHandler handler : handlers) {
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack fluid = handler.getFluidInTank(tank);
                    if (!fluid.isEmpty()) {
                        action.accept(fluid, fluid.getAmount());
                    }
                }
            }
        }

        @Override
        public String chartKey(FluidStack kind) {
            return "fluid:" + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(kind.getFluid());
        }

        @Override
        public boolean sameKind(FluidStack a, FluidStack b) {
            return FluidStack.isSameFluidSameComponents(a, b);
        }

        @Override
        public boolean passes(CompiledSheet.Gate gate, FluidStack kind, Filters.Context context) {
            return gate.fluids().test(kind, context) != gate.reject();
        }

        @Override
        public boolean matches(CompiledSheet sheet, NodeSettings.Limit limit, FluidStack kind, Filters.Context context) {
            return sheet.fluidFilter(limit).test(kind, context);
        }

        @Override
        public Filters.Context context(ServerLevel level, List<IFluidHandler> sinkView) {
            return new Filters.Context(level, null, f -> TankFlow.contains(this, sinkView, f));
        }

        @Override
        public long perTick() {
            return ReticulumConfig.FLUID_PER_TICK.getAsInt();
        }

        @Override
        public boolean overflowFull(Network network) {
            return Overflow.fluidsFull(network);
        }

        @Override
        public void overflow(NetworkRuntime.Context ctx, FluidStack kind, long amount) {
            Overflow.add(ctx, kind.copyWithAmount((int) Math.min(amount, Integer.MAX_VALUE)));
        }

        private static IFluidHandler.FluidAction action(boolean simulate) {
            return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
        }
    };

    // ---------------------------------------------------------------- sharing

    /**
     * Lowers demands so that routes sharing a rate limit (a limit before a splitter, say) together ask for no more
     * than it has left, rather than each for all of it.
     */
    static void capByRateLimits(NetworkRuntime runtime, List<CompiledSheet.Route> routes, long[] demand) {
        Map<UUID, Long> left = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            long allowed = demand[i];
            for (var limit : routes.get(i).rateLimits()) {
                allowed = Math.min(allowed, left.computeIfAbsent(limit.id(), id -> runtime.available(List.of(limit))));
            }
            demand[i] = Math.max(0, allowed);
            for (var limit : routes.get(i).rateLimits()) {
                left.merge(limit.id(), -demand[i], Long::sum);
            }
        }
    }

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

    /**
     * Each route's weight: for each weighted splitter on it, its output's weight shared by the routes going out of that
     * output (so an output weighted 3 that fans out to two sinks gives each 1.5, together 3, as items get). 1 without
     * any. Scaled to whole numbers.
     */
    static long[] weights(List<CompiledSheet.Route> routes) {
        return weights(routes, null);
    }

    /** As {@link #weights(List)}, an output's weight shared only by the routes out of it that want something ({@code demand} > 0). */
    static long[] weights(List<CompiledSheet.Route> routes, @javax.annotation.Nullable long[] demand) {
        // How many routes go out of each weighted splitter's output.
        Map<java.util.List<Object>, Integer> sharing = new java.util.HashMap<>();
        for (int r = 0; r < routes.size(); r++) {
            if (demand != null && demand[r] <= 0) {
                continue;
            }
            CompiledSheet.Route route = routes.get(r);
            for (CompiledSheet.Branch branch : route.branches()) {
                if (branch.mode() == NodeSettings.SplitMode.WEIGHTED) {
                    sharing.merge(java.util.List.of(branch.node(), branch.index()), 1, Integer::sum);
                }
            }
        }
        double[] exact = new double[routes.size()];
        double largest = 0;
        for (int i = 0; i < exact.length; i++) {
            double weight = 1;
            for (CompiledSheet.Branch branch : routes.get(i).branches()) {
                if (branch.mode() == NodeSettings.SplitMode.WEIGHTED) {
                    weight *= Math.max(0, branch.weight()) / (double) sharing.getOrDefault(java.util.List.of(branch.node(), branch.index()), 1);
                }
            }
            exact[i] = weight;
            largest = Math.max(largest, weight);
        }
        // Whole numbers keeping the ratios: the largest at 2^30 (a sum of many can't overflow), none above 0 rounded to 0.
        long[] weights = new long[routes.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = exact[i] <= 0 || largest <= 0 ? 0 : Math.max(1, Math.round(exact[i] / largest * (1L << 30)));
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
