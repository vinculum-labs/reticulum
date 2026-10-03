package io.github.leadassimilator.reticulum.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Throughput per node, per wire and per device input and output: what moved in the last second, a 5-minute window at one value per second and
 * a 24-hour window at one value per minute. Kept in memory only; a series exists once something has moved through it.
 */
public final class Metrics {
    public static final int SECONDS = 300;
    public static final int MINUTES = 1440;

    private static final class Series {
        long current;
        long lastMovedTick = -1;
        long lastSecond;
        final long[] seconds = new long[SECONDS];
        int secondIndex;
        final long[] minutes = new long[MINUTES];
        int minuteIndex;
        long minuteTotal;
        int secondsIntoMinute;

        void rollSecond() {
            lastSecond = current;
            current = 0;
            seconds[secondIndex] = lastSecond;
            secondIndex = (secondIndex + 1) % SECONDS;
            minuteTotal += lastSecond;
            if (++secondsIntoMinute == 60) {
                minutes[minuteIndex] = minuteTotal;
                minuteIndex = (minuteIndex + 1) % MINUTES;
                minuteTotal = 0;
                secondsIntoMinute = 0;
            }
        }
    }

    private final Map<UUID, Series> series = new HashMap<>();

    void record(CompiledSheet.Route route, long amount, long tick) {
        if (amount <= 0) {
            return;
        }
        for (UUID node : route.path()) {
            Series s = series.computeIfAbsent(node, k -> new Series());
            s.current += amount;
            s.lastMovedTick = tick;
        }
        for (UUID wire : route.wires()) {
            Series s = series.computeIfAbsent(wire, k -> new Series());
            s.current += amount;
            s.lastMovedTick = tick;
        }
        // The device input and output it ran between (a side of a device node has an id of its own), for the
        // per-side rates; not when that is a node already counted on the path.
        for (UUID side : new UUID[] {route.source().id(), route.sink().id()}) {
            if (!route.path().contains(side)) {
                Series s = series.computeIfAbsent(side, k -> new Series());
                s.current += amount;
                s.lastMovedTick = tick;
            }
        }
    }

    /** Total moved over the last 5 minutes, per id. */
    public Map<UUID, Long> lastFiveMinutes() {
        Map<UUID, Long> out = new HashMap<>();
        series.forEach((id, s) -> {
            long total = 0;
            for (long v : s.seconds) {
                total += v;
            }
            out.put(id, total);
        });
        return out;
    }

    /** Ticks since each id last moved anything. */
    public Map<UUID, Long> ticksSinceMoved(long now) {
        Map<UUID, Long> out = new HashMap<>();
        series.forEach((id, s) -> {
            if (s.lastMovedTick >= 0) {
                out.put(id, now - s.lastMovedTick);
            }
        });
        return out;
    }

    /** Called every tick; closes a second every 20 ticks. */
    void tick(long ticks) {
        if (ticks % 20 == 0) {
            series.values().forEach(Series::rollSecond);
        }
    }

    /** Amount moved in the last whole second, per node or wire id. Ids with nothing recorded are left out. */
    public Map<UUID, Long> lastSecond() {
        Map<UUID, Long> out = new HashMap<>();
        series.forEach((id, s) -> out.put(id, s.lastSecond));
        return out;
    }

    /** Oldest first: 300 per-second values, or 1440 per-minute values. Zeros for an id that has no series. */
    public long[] history(UUID id, boolean perMinute) {
        Series s = series.get(id);
        int size = perMinute ? MINUTES : SECONDS;
        long[] out = new long[size];
        if (s == null) {
            return out;
        }
        long[] ring = perMinute ? s.minutes : s.seconds;
        int start = perMinute ? s.minuteIndex : s.secondIndex;
        for (int i = 0; i < size; i++) {
            out[i] = ring[(start + i) % size];
        }
        return out;
    }
}
