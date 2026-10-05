package io.github.leadassimilator.reticulum.block;

import io.github.leadassimilator.reticulum.util.LowercaseName;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * How far a cable or connector has been turned (and whether mirrored) since its per-side data was written: what is
 * kept by side (joints cut, device sides switched off) is kept as it was laid out, and read through this. Structures,
 * schematics and contraptions turn a block's state but never its block entity, so this turns with them and the cuts
 * follow. Only the game's own turns come through: around the vertical axis, and mirrored.
 */
public enum Orientation implements LowercaseName {
    TURN_0(0, false),
    TURN_90(1, false),
    TURN_180(2, false),
    TURN_270(3, false),
    MIRRORED_0(0, true),
    MIRRORED_90(1, true),
    MIRRORED_180(2, true),
    MIRRORED_270(3, true);

    /** Quarter turns clockwise (seen from above), after the mirror. */
    private final int turns;
    /** Mirrored north to south, before the turn. */
    private final boolean mirrored;

    Orientation(int turns, boolean mirrored) {
        this.turns = turns;
        this.mirrored = mirrored;
    }

    /** The side in the world that a side as it was laid out faces now. */
    public Direction toWorld(Direction laid) {
        Direction side = mirrored ? Mirror.LEFT_RIGHT.mirror(laid) : laid;
        for (int i = 0; i < turns; i++) {
            side = Rotation.CLOCKWISE_90.rotate(side);
        }
        return side;
    }

    /** The side as it was laid out that faces {@code world} now. */
    public Direction toLaid(Direction world) {
        for (Direction laid : Direction.values()) {
            if (toWorld(laid) == world) {
                return laid;
            }
        }
        throw new IllegalStateException();
    }

    public Orientation rotate(Rotation rotation) {
        return matching(this, rotation::rotate);
    }

    public Orientation mirror(Mirror mirror) {
        return matching(this, mirror::mirror);
    }

    /** The orientation that is this one followed by {@code then}: two sides at right angles settle it. */
    private static Orientation matching(Orientation from, java.util.function.UnaryOperator<Direction> then) {
        for (Orientation to : values()) {
            if (to.toWorld(Direction.NORTH) == then.apply(from.toWorld(Direction.NORTH))
                    && to.toWorld(Direction.EAST) == then.apply(from.toWorld(Direction.EAST))) {
                return to;
            }
        }
        throw new IllegalStateException();
    }
}
