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
        TankFlow.run(ctx, sheet, source, CHEMICALS);
    }

    static final TankFlow.Tank<IChemicalHandler, ChemicalStack> CHEMICALS = new TankFlow.Tank<>() {
        @Override
        public List<IChemicalHandler> handlers(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().handlers(ctx.server(), ctx.network(), port, MekanismChemicals.CAPABILITY, ResourceKind.CHEMICAL);
        }

        @Override
        public List<IChemicalHandler> view(NetworkRuntime.Context ctx, NodeSettings.Port port) {
            return ctx.runtime().view(ctx.server(), ctx.network(), port, MekanismChemicals.CAPABILITY, ResourceKind.CHEMICAL);
        }

        @Override
        public List<ChemicalStack> kinds(List<IChemicalHandler> handlers) {
            List<ChemicalStack> kinds = new ArrayList<>();
            forEachStored(handlers, (chemical, amount) -> {
                if (kinds.stream().noneMatch(k -> ChemicalStack.isSameChemical(k, chemical))) {
                    kinds.add(chemical.copyWithAmount(1));
                }
            });
            return kinds;
        }

        @Override
        public long extract(IChemicalHandler handler, ChemicalStack kind, long max, boolean simulate) {
            return handler.extractChemical(kind.copyWithAmount(max), action(simulate)).getAmount();
        }

        @Override
        public long insert(IChemicalHandler handler, ChemicalStack kind, long amount, boolean simulate) {
            return amount - handler.insertChemical(kind.copyWithAmount(amount), action(simulate)).getAmount();
        }

        @Override
        public void forEachStored(List<IChemicalHandler> handlers, TankFlow.StoredConsumer<ChemicalStack> action) {
            for (IChemicalHandler handler : handlers) {
                for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                    ChemicalStack chemical = handler.getChemicalInTank(tank);
                    if (!chemical.isEmpty()) {
                        action.accept(chemical, chemical.getAmount());
                    }
                }
            }
        }

        @Override
        public String chartKey(ChemicalStack kind) {
            return "chemical:" + io.github.leadassimilator.reticulum.integration.mekanism.MekanismChemicals.id(kind);
        }

        @Override
        public boolean sameKind(ChemicalStack a, ChemicalStack b) {
            return ChemicalStack.isSameChemical(a, b);
        }

        @Override
        public boolean passes(CompiledSheet.Gate gate, ChemicalStack kind, Filters.Context context) {
            return gate.chemicals().test(kind, context) != gate.reject();
        }

        @Override
        public boolean matches(CompiledSheet sheet, NodeSettings.Limit limit, ChemicalStack kind, Filters.Context context) {
            return sheet.chemicalFilter(limit).test(kind, context);
        }

        @Override
        public Filters.Context context(ServerLevel level, List<IChemicalHandler> sinkView) {
            return new Filters.Context(level, null, null, c -> TankFlow.contains(this, sinkView, (ChemicalStack) c));
        }

        @Override
        public long perTick() {
            return ReticulumConfig.CHEMICAL_PER_TICK.getAsLong();
        }

        @Override
        public boolean overflowFull(Network network) {
            return full(network);
        }

        @Override
        public void overflow(NetworkRuntime.Context ctx, ChemicalStack kind, long amount) {
            ctx.network().overflow().chemicals().merge(MekanismChemicals.id(kind), amount, Long::sum);
            NetworkManager.overflowChanged(ctx.server(), ctx.network());
        }

        private static Action action(boolean simulate) {
            return simulate ? Action.SIMULATE : Action.EXECUTE;
        }
    };

    /** mB of matching chemicals a port holds, for level sensors. */
    static long count(NetworkRuntime.Context ctx, NodeSettings.Port port, Filters.ChemicalFilter filter, Filters.Context context) {
        long[] total = {0};
        CHEMICALS.forEachStored(CHEMICALS.view(ctx, port), (chemical, amount) -> {
            if (filter.test(chemical, context)) {
                total[0] = TankFlow.saturatedAdd(total[0], amount);
            }
        });
        return total[0];
    }

    // ---------------------------------------------------------------- overflow

    static boolean full(Network network) {
        long limit = ReticulumConfig.OVERFLOW_CHEMICAL_BUCKETS.getAsInt() * 1000L;
        return network.overflow().chemicals().values().stream().anyMatch(amount -> amount >= limit);
    }

    /** Tries to deliver buffered chemicals along one route. */
    static boolean retry(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Route route, ServerLevel level) {
        List<IChemicalHandler> to = CHEMICALS.handlers(ctx, route.sinkSettings().port());
        List<IChemicalHandler> view = CHEMICALS.view(ctx, route.sinkSettings().port());
        boolean changed = false;
        Filters.Context context = CHEMICALS.context(level, view);
        Iterator<Map.Entry<ResourceLocation, Long>> it = ctx.network().overflow().chemicals().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, Long> held = it.next();
            ChemicalStack stack = MekanismChemicals.stack(held.getKey(), held.getValue());
            if (stack == null) {
                // A chemical whose mod is gone can never be delivered; keeping it would block chemical flows for good.
                io.github.leadassimilator.reticulum.Reticulum.LOGGER.warn("Dropping {} mB of unknown chemical {} from {}'s overflow buffer",
                        held.getValue(), held.getKey(), ctx.network().name());
                it.remove();
                changed = true;
                continue;
            }
            if (!TankFlow.passes(CHEMICALS, route.gates(), stack, context)) {
                continue;
            }
            long room = Math.min(TankFlow.limit(CHEMICALS, sheet, route.sinkSettings().caps(), view, stack, context, false),
                    ctx.runtime().available(route.rateLimits()));
            if (room <= 0) {
                continue;
            }
            ChemicalStack left = stack.copyWithAmount(Math.min(stack.getAmount(), room));
            long offered = left.getAmount();
            for (IChemicalHandler handler : to) {
                left = handler.insertChemical(left, Action.EXECUTE);
            }
            long placed = offered - left.getAmount();
            if (placed > 0) {
                ctx.runtime().consume(route.rateLimits(), placed);
                changed = true;
                if (placed == held.getValue()) {
                    it.remove();
                } else {
                    held.setValue(held.getValue() - placed);
                }
            }
        }
        return changed;
    }
}
