package io.github.leadassimilator.reticulum.gametest;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/** What every GameTest class needs. */
final class TestSupport {
    private TestSupport() {
    }

    /** Places a block the way a player would, clicking against the block on side {@code against} (may be null). */
    static void place(GameTestHelper helper, BlockPos rel, Block block, @Nullable Direction against) {
        helper.setBlock(rel, block);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(rel), against, null);
    }

    static void place(GameTestHelper helper, BlockPos rel, Block block) {
        place(helper, rel, block, null);
    }

    /** Fails the test with {@code message} unless {@code condition} holds. */
    static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }

    /** Fails the test with {@code message}. */
    static void fail(GameTestHelper helper, String message) {
        helper.fail(Component.literal(message));
    }
}
