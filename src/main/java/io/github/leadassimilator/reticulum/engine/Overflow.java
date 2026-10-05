package io.github.leadassimilator.reticulum.engine;

import java.util.Iterator;
import java.util.List;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The network's overflow buffer: resources a sink refused after a successful simulation and the source wouldn't
 * take back. Nothing is ever voided; while the buffer is full, item and fluid flows pause.
 */
final class Overflow {
    private Overflow() {
    }

    static void add(NetworkRuntime.Context ctx, ItemStack stack) {
        List<ItemStack> items = ctx.network().overflowItems();
        ItemStack left = stack.copy();
        for (ItemStack held : items) {
            if (ItemStack.isSameItemSameComponents(held, left) && held.getCount() < held.getMaxStackSize()) {
                int moved = Math.min(left.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(moved);
                left.shrink(moved);
            }
        }
        if (!left.isEmpty()) {
            items.add(left);
        }
        NetworkManager.overflowChanged(ctx.server(), ctx.network());
    }

    static void add(NetworkRuntime.Context ctx, FluidStack stack) {
        List<FluidStack> fluids = ctx.network().overflowFluids();
        for (FluidStack held : fluids) {
            if (FluidStack.isSameFluidSameComponents(held, stack)) {
                held.grow(stack.getAmount());
                NetworkManager.overflowChanged(ctx.server(), ctx.network());
                return;
            }
        }
        fluids.add(stack.copy());
        NetworkManager.overflowChanged(ctx.server(), ctx.network());
    }

    static boolean itemsFull(Network network) {
        return network.overflowItems().size() >= ReticulumConfig.OVERFLOW_ITEM_STACKS.getAsInt();
    }

    static boolean fluidsFull(Network network) {
        long limit = ReticulumConfig.OVERFLOW_FLUID_BUCKETS.getAsInt() * 1000L;
        for (FluidStack fluid : network.overflowFluids()) {
            if (fluid.getAmount() >= limit) {
                return true;
            }
        }
        return false;
    }

    /** Tries to deliver buffered resources to any sink on the network whose route would accept them. */
    static void retry(NetworkRuntime.Context ctx, List<CompiledSheet> sheets) {
        Network network = ctx.network();
        if (network.overflow().isEmpty() || !ctx.runtime().powered()) {
            return;
        }
        boolean changed = false;
        for (CompiledSheet sheet : sheets) {
            for (CompiledSheet.Source source : sheet.sources()) {
                if (!ctx.runtime().active(source)) {
                    continue;
                }
                // Only routes redstone leaves on, and only as much as their rate limits allow.
                for (CompiledSheet.Route route : ctx.runtime().routes(source)) {
                    if (route.kit() != null || ctx.runtime().available(route.rateLimits()) <= 0) {
                        continue;
                    }
                    ServerLevel level = ctx.server().getLevel(route.sinkSettings().port().device().dimension());
                    if (level == null) {
                        continue;
                    }
                    if (source.resource() == ResourceKind.ITEM) {
                        changed |= retryItems(ctx, sheet, route, level);
                    } else if (source.resource() == ResourceKind.FLUID) {
                        changed |= retryFluids(ctx, sheet, route, level);
                    } else if (source.resource() == ResourceKind.CHEMICAL && Mods.MEKANISM) {
                        changed |= ChemicalMover.retry(ctx, sheet, route, level);
                    }
                }
            }
        }
        if (changed) {
            NetworkManager.overflowChanged(ctx.server(), ctx.network());
        }
    }

    private static boolean retryItems(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Route route, ServerLevel level) {
        ItemPort to = ctx.runtime().itemPort(ctx.server(), ctx.network(), route.sinkSettings().port());
        if (to.isEmpty()) {
            return false;
        }
        boolean changed = false;
        Filters.Context context = new Filters.Context(level, to::contains, null);
        Iterator<ItemStack> it = ctx.network().overflowItems().iterator();
        while (it.hasNext()) {
            ItemStack held = it.next();
            if (!ItemMover.passes(route.gates(), held, context)) {
                continue;
            }
            long room = Math.min(to.limit(route.sinkSettings().caps(), sheet, held, context, false), ctx.runtime().available(route.rateLimits()));
            if (room <= 0) {
                continue;
            }
            ItemStack offer = held.copyWithCount((int) Math.min(held.getCount(), room));
            int placed = offer.getCount() - to.insert(offer, false).getCount();
            if (placed > 0) {
                ctx.runtime().consume(route.rateLimits(), placed);
                held.shrink(placed);
                changed = true;
                if (held.isEmpty()) {
                    it.remove();
                }
            }
        }
        return changed;
    }

    private static boolean retryFluids(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Route route, ServerLevel level) {
        List<IFluidHandler> to = ctx.runtime().fluids(ctx.server(), ctx.network(), route.sinkSettings().port());
        List<IFluidHandler> view = ctx.runtime().fluidView(ctx.server(), ctx.network(), route.sinkSettings().port());
        boolean changed = false;
        Filters.Context context = ContinuousMover.FLUIDS.context(level, view);
        Iterator<FluidStack> it = ctx.network().overflowFluids().iterator();
        while (it.hasNext()) {
            FluidStack held = it.next();
            if (!TankFlow.passes(ContinuousMover.FLUIDS, route.gates(), held, context)) {
                continue;
            }
            long room = Math.min(TankFlow.limit(ContinuousMover.FLUIDS, sheet, route.sinkSettings().caps(), view, held, context, false),
                    ctx.runtime().available(route.rateLimits()));
            for (IFluidHandler handler : to) {
                if (room <= 0 || held.isEmpty()) {
                    break;
                }
                int filled = handler.fill(held.copyWithAmount((int) Math.min(held.getAmount(), room)), IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    ctx.runtime().consume(route.rateLimits(), filled);
                    room -= filled;
                    held.shrink(filled);
                    changed = true;
                }
            }
            if (held.isEmpty()) {
                it.remove();
            }
        }
        return changed;
    }
}
