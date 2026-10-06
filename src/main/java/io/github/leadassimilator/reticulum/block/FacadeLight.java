package io.github.leadassimilator.reticulum.block;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The light of each glowing facade, by world and position. The light engine works on a thread of its own, where a
 * world hands out no block entities, so it can't read the facade itself: it reads this, kept as facades are put on,
 * loaded, taken off and unloaded.
 */
public final class FacadeLight {
    private static final Map<Level, Map<Long, Integer>> LIGHTS = Collections.synchronizedMap(new WeakHashMap<>());

    private FacadeLight() {
    }

    /** Records the light a facade at {@code pos} gives (0: none, or no facade). */
    public static void set(@Nullable Level level, BlockPos pos, int light) {
        if (level == null) {
            return;
        }
        if (light > 0) {
            LIGHTS.computeIfAbsent(level, l -> new ConcurrentHashMap<>()).put(pos.asLong(), light);
        } else {
            Map<Long, Integer> lights = LIGHTS.get(level);
            if (lights != null) {
                lights.remove(pos.asLong());
            }
        }
    }

    /** The light recorded for a facade at {@code pos}, read through a world or one of its chunks; 0 when none. */
    public static int get(BlockGetter getter, BlockPos pos) {
        Level level = getter instanceof Level l ? l : getter instanceof LevelChunk chunk ? chunk.getLevel() : null;
        Map<Long, Integer> lights = level == null ? null : LIGHTS.get(level);
        return lights == null ? 0 : lights.getOrDefault(pos.asLong(), 0);
    }
}
