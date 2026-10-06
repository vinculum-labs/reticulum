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
        /** A level (a device's contents), set every second, rather than something moved. */
        boolean sampled;
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

    /** Ticks in the longest window: a series that moved nothing for that long holds nothing but zeros. */
    private static final long DAY_TICKS = MINUTES * 60L * 20L;

    /**
     * Drops series that moved nothing for a whole day (a wire or node long gone, or idle): all they hold is zeros, so
     * nothing shown changes, and they stop being rolled and sent every second. Levels are kept (set every second).
     */
    void prune(long now) {
        series.entrySet().removeIf(e -> {
            Series s = e.getValue();
            if (s.sampled || now - Math.max(0, s.lastMovedTick) <= DAY_TICKS + 20 * 60) {
                return false;
            }
            if (s.lastMovedTick >= 0) {
                movedLongAgo.put(e.getKey(), s.lastMovedTick);
            }
            return true;
        });
        // Held to a bound: the oldest go first (they then read as never moved).
        if (movedLongAgo.size() > 4096) {
            movedLongAgo.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(movedLongAgo.size() - 4096L)
                    .map(Map.Entry::getKey).toList().forEach(movedLongAgo::remove);
        }
    }

    /** When pruned series last moved, so "last moved" still says it (rather than "never"). */
    private final Map<UUID, Long> movedLongAgo = new HashMap<>();

    /** The series to add to (made when missing: then no longer one that moved long ago). */
    private Series fresh(UUID id) {
        Series s = series.get(id);
        if (s == null) {
            s = new Series();
            series.put(id, s);
            movedLongAgo.remove(id);
        }
        return s;
    }

    void record(CompiledSheet.Route route, long amount, long tick) {
        if (amount <= 0) {
            return;
        }
        for (UUID node : route.path()) {
            Series s = fresh(node);
            s.current += amount;
            s.lastMovedTick = tick;
        }
        for (UUID wire : route.wires()) {
            Series s = fresh(wire);
            s.current += amount;
            s.lastMovedTick = tick;
        }
        // The device input and output it ran between (a side of a device node has an id of its own), for the
        // per-side rates; not when that is a node already counted on the path.
        for (UUID side : new UUID[] {route.source().id(), route.sink().id()}) {
            if (!route.path().contains(side)) {
                Series s = fresh(side);
                s.current += amount;
                s.lastMovedTick = tick;
            }
        }
    }

    /** Adds to one series by its id (a chart's: a resource's total, or an item, fluid or chemical charted). */
    void recordId(UUID id, long amount, long tick) {
        if (amount <= 0) {
            return;
        }
        Series s = fresh(id);
        s.current += amount;
        s.lastMovedTick = tick;
    }

    /** Sets a level series for this second (a device's contents): a minute's value is then the sum of 60 samples. */
    void sample(UUID id, long value) {
        Series s = series.computeIfAbsent(id, k -> new Series());
        s.sampled = true;
        s.current = value;
    }

    /** Forgets series no longer charted. */
    void forget(UUID id) {
        series.remove(id);
        movedLongAgo.remove(id);
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
        movedLongAgo.forEach((id, tick) -> out.put(id, now - tick));
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
        if (ticks % 1200 == 0) {
            prune(ticks);
        }
    }

    /** Amount moved in the last whole second, per node or wire id. Ids with nothing recorded are left out. */
    public Map<UUID, Long> lastSecond() {
        Map<UUID, Long> out = new HashMap<>();
        series.forEach((id, s) -> out.put(id, s.lastSecond));
        return out;
    }

    /** Oldest first: 300 per-second values, or 1440 per-minute values. Zeros for an id that has no series. */
    /** Seconds since the series' newest whole minute ended (0 for one never recorded). */
    public int secondsIntoMinute(UUID id) {
        Series s = series.get(id);
        return s == null ? 0 : s.secondsIntoMinute;
    }

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
