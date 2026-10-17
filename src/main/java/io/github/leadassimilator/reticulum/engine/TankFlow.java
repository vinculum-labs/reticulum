package io.github.leadassimilator.reticulum.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.network.Network;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Energy, fluid and chemical flows: one algorithm, with a {@link Tank} adapter per resource. Every tick each sink
 * says how much it can take, the source supplies what it can, and the supply is shared evenly (or in order, or by
 * weight); each kind in the source (each fluid, each chemical) moves on its own.
 *
 * <p>Amounts are counted through each block's view, which sees every store once, and what a block can give or take
 * is the best single face's answer: a block that wraps one store on every face (a Mekanism machine) would otherwise
 * be counted several times over.
 */
final class TankFlow {
    private TankFlow() {
    }

    /**
     * One kind of continuous resource, as the flow needs it. {@code H} is its handler (one face of a block), {@code S}
     * a kind of it (a fluid, a chemical; energy has one).
     */
    interface Tank<H, S> {
        /** The handlers to move through: one per face that answers ("auto"), or the chosen faces'. */
        List<H> handlers(NetworkRuntime.Context ctx, NodeSettings.Port port);

        /** The handlers to count with: the unsided one when no faces are chosen, so each store is counted once. */
        List<H> view(NetworkRuntime.Context ctx, NodeSettings.Port port);

        /** The kinds present, each moved on its own. */
        List<S> kinds(List<H> handlers);

        /** Takes (or would take) up to {@code max} of a kind; returns how much. */
        long extract(H handler, S kind, long max, boolean simulate);

        /** Puts (or would put) up to {@code amount} of a kind in; returns how much it took. */
        long insert(H handler, S kind, long amount, boolean simulate);

        /** Gives every stored amount in the handlers, with its kind (empty stores skipped). */
        void forEachStored(List<H> handlers, StoredConsumer<S> action);

        boolean sameKind(S a, S b);

        /** Whether a gate (a filter node on the route) lets this kind through. */
        boolean passes(CompiledSheet.Gate gate, S kind, Filters.Context context);

        /** Whether a keep rule or cap applies to this kind (its filter matches). */
        boolean matches(CompiledSheet sheet, NodeSettings.Limit limit, S kind, Filters.Context context);

        /** The context filters are tested in for a sink (what {@code in:sink} sees). */
        Filters.Context context(ServerLevel level, List<H> sinkView);

        /** The most one route moves per tick. */
        long perTick();

        /** Whether the network's overflow buffer for this resource is full, which pauses its flows. */
        boolean overflowFull(Network network);

        /** Keeps what neither the sink nor the source would take, so nothing is lost. */
        void overflow(NetworkRuntime.Context ctx, S kind, long amount);

        /** What a delivery of this kind is charted as ("fluid:minecraft:water"), or null (energy: only its total). */
        default String chartKey(S kind) {
            return null;
        }
    }

    @FunctionalInterface
    interface StoredConsumer<S> {
        void accept(S kind, long amount);
    }

    /** What one kind's movement came to: how much moved, and if nothing did, why. */
    private record Outcome(long moved, Status why) {
    }

    static <H, S> void run(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, Tank<H, S> tank) {
        UUID owner = sheet.owner(source.node().id());
        if (tank.overflowFull(ctx.network())) {
            ctx.runtime().status(owner, Status.OVERFLOW_FULL);
            return;
        }
        List<H> from = tank.handlers(ctx, source.settings().port());
        if (from.isEmpty()) {
            ctx.runtime().status(owner, Status.DEVICE_OFFLINE);
            return;
        }
        ServerLevel level = ctx.server().getLevel(source.settings().port().device().dimension());
        List<H> fromView = tank.view(ctx, source.settings().port());
        List<CompiledSheet.Route> routes = ctx.runtime().ordered(ctx.runtime().routes(source));
        long total = 0;
        Status why = Status.SOURCE_EMPTY;
        for (S kind : tank.kinds(from)) {
            Outcome outcome = moveKind(ctx, sheet, source, tank, level, from, fromView, kind, routes);
            total += outcome.moved();
            if (outcome.moved() == 0) {
                why = Status.furthest(why, outcome.why());
            }
        }
        ctx.runtime().status(owner, total > 0 ? null : why);
    }

    private static <H, S> Outcome moveKind(NetworkRuntime.Context ctx, CompiledSheet sheet, CompiledSheet.Source source, Tank<H, S> tank,
            ServerLevel level, List<H> from, List<H> fromView, S kind, List<CompiledSheet.Route> ordered) {
        long supply = 0;
        for (H handler : from) {
            supply = Math.max(supply, tank.extract(handler, kind, Long.MAX_VALUE, true));
        }
        if (supply <= 0) {
            return new Outcome(0, Status.SOURCE_EMPTY);
        }
        supply = Math.min(supply, limit(tank, sheet, source.settings().keep(), fromView, kind, Filters.Context.of(level), true));
        if (supply <= 0) {
            return new Outcome(0, Status.KEEP_RULE);
        }
        Status why = Status.TARGET_FULL;
        List<CompiledSheet.Route> routes = new ArrayList<>();
        List<List<H>> sinks = new ArrayList<>();
        List<Long> demands = new ArrayList<>();
        // Room left in each target this tick: several routes may end at the same block (two inputs of one machine).
        // Free room as each set of faces sees it (a machine's input side may have room its output side hasn't), less
        // what is already planned into the same handlers (one store reached several ways counts once).
        Map<List<Object>, Long> room = new HashMap<>();
        Map<Object, Long> plannedInto = new IdentityHashMap<>();
        Map<GlobalPos, Long> plannedDevice = new HashMap<>();
        Map<GlobalPos, Long> plannedAuto = new HashMap<>();
        // What each sink node's caps still allow this tick, once a route into it has asked.
        Map<UUID, Long> capLeft = new HashMap<>();
        for (CompiledSheet.Route route : ordered) {
            NodeSettings.Port port = route.sinkSettings().port();
            List<H> to = tank.handlers(ctx, port);
            if (to.isEmpty()) {
                why = Status.furthest(why, Status.TARGET_OFFLINE);
                continue;
            }
            List<H> toView = tank.view(ctx, port);
            Filters.Context context = tank.context(level, toView);
            if (!passes(tank, route.gates(), kind, context)) {
                why = Status.furthest(why, Status.FILTERED_OUT);
                continue;
            }
            // Shared by every route into the same sink node this tick (a splitter's branches meeting again): each takes
            // from what the cap leaves, rather than each seeing the whole of it.
            long capRoom = Math.min(limit(tank, sheet, route.sinkSettings().caps(), toView, kind, context, false),
                    capLeft.getOrDefault(route.sink().id(), Long.MAX_VALUE));
            long max = Math.min(Math.min(tank.perTick(), ctx.runtime().available(route.rateLimits())), capRoom);
            if (max <= 0) {
                why = Status.furthest(why, capRoom <= 0 ? Status.CAP_REACHED : Status.RATE_LIMITED);
                continue;
            }
            List<Object> through = List.of(port.device(), Set.copyOf(port.faces()));
            long seen = room.computeIfAbsent(through, key -> {
                long best = 0;
                for (H handler : to) {
                    best = Math.max(best, tank.insert(handler, kind, Long.MAX_VALUE, true));
                }
                return best;
            });
            // Planned already into what these handlers reach: a handler another route uses too is the same store (one
            // tank seen through two faces), a different one may be a separate tank (a machine's two inputs).
            long already = 0;
            for (H handler : to) {
                already = Math.max(already, plannedInto.getOrDefault(handler, 0L));
            }
            // Through all faces (auto): everything planned into the device; through some: what went through all of them.
            already = Math.max(already, port.faces().isEmpty() ? plannedDevice.getOrDefault(port.device(), 0L)
                    : plannedAuto.getOrDefault(port.device(), 0L));
            long free = seen - already;
            long demand = Math.max(0, Math.min(max, free));
            for (H handler : to) {
                plannedInto.merge(handler, demand, Long::sum);
            }
            plannedDevice.merge(port.device(), demand, Long::sum);
            if (port.faces().isEmpty()) {
                plannedAuto.merge(port.device(), demand, Long::sum);
            }
            if (!route.sinkSettings().caps().isEmpty()) {
                capLeft.put(route.sink().id(), capRoom - demand);
            }
            routes.add(route);
            sinks.add(to);
            demands.add(demand);
        }
        long[] demand = demands.stream().mapToLong(Long::longValue).toArray();
        ContinuousMover.capByRateLimits(ctx.runtime(), routes, demand);
        long[] share = ContinuousMover.share(supply, demand, ContinuousMover.anyPriority(routes), ContinuousMover.weights(routes, demand));
        long total = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (share[i] <= 0) {
                continue;
            }
            long moved = move(ctx, tank, from, sinks.get(i), kind, share[i]);
            if (moved > 0) {
                total += moved;
                ctx.runtime().delivered(routes.get(i), moved, tank.chartKey(kind));
                ctx.runtime().advance(routes.get(i));
            }
        }
        return new Outcome(total, why);
    }

    /** Takes {@code amount} from the source and puts it in the sink; what the sink refuses goes back, else to overflow. */
    private static <H, S> long move(NetworkRuntime.Context ctx, Tank<H, S> tank, List<H> from, List<H> to, S kind, long amount) {
        // The room asked again now: an earlier route may have filled what this one was promised (two sinks sharing one
        // buffer), and what is taken without room may have nowhere to go.
        long room = 0;
        for (H handler : to) {
            if (room >= amount) {
                break;
            }
            room += tank.insert(handler, kind, amount - room, true);
        }
        amount = Math.min(amount, room);
        if (amount <= 0) {
            return 0;
        }
        long taken = 0;
        for (H handler : from) {
            if (taken >= amount) {
                break;
            }
            taken += tank.extract(handler, kind, amount - taken, false);
        }
        long left = taken;
        for (H handler : to) {
            if (left <= 0) {
                break;
            }
            left -= tank.insert(handler, kind, left, false);
        }
        long delivered = taken - left;
        for (H handler : from) {
            if (left <= 0) {
                break;
            }
            left -= tank.insert(handler, kind, left, false);
        }
        if (left > 0) {
            tank.overflow(ctx, kind, left);
        }
        return delivered;
    }

    static <H, S> boolean passes(Tank<H, S> tank, List<CompiledSheet.Gate> gates, S kind, Filters.Context context) {
        for (CompiledSheet.Gate gate : gates) {
            if (!tank.passes(gate, kind, context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Room left by keep rules (a source: how much may go) or caps (a sink: how much may come) for one kind, counted
     * through {@code view}.
     */
    static <H, S> long limit(Tank<H, S> tank, CompiledSheet sheet, List<NodeSettings.Limit> limits, List<H> view, S kind,
            Filters.Context context, boolean keep) {
        long allowed = Long.MAX_VALUE;
        for (NodeSettings.Limit limit : limits) {
            if (sheet.broken(limit)) {
                return 0;
            }
            if (!tank.matches(sheet, limit, kind, context)) {
                continue;
            }
            long[] present = {0};
            tank.forEachStored(view, (held, amount) -> {
                boolean counted = switch (limit.scope()) {
                    case PER_TYPE -> tank.sameKind(held, kind);
                    case TOTAL -> true;
                    case FILTER -> tank.matches(sheet, limit, held, context);
                };
                if (counted) {
                    present[0] = saturatedAdd(present[0], amount);
                }
            });
            allowed = Math.min(allowed, Math.max(0, keep ? present[0] - limit.amount() : limit.amount() - present[0]));
        }
        return allowed;
    }

    /** Whether any handler holds this kind (what {@code in:sink} asks). */
    static <H, S> boolean contains(Tank<H, S> tank, List<H> handlers, S kind) {
        boolean[] found = {false};
        tank.forEachStored(handlers, (held, amount) -> found[0] |= tank.sameKind(held, kind));
        return found[0];
    }

    /** Adds amounts without wrapping: a creative tank reports the largest amount there is. */
    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
