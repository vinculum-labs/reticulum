package io.github.leadassimilator.reticulum.engine;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Calls into other mods' item handlers that can't lose or make items when one throws: a call that fails is taken as
 * having done nothing (it took nothing, gave nothing), so what is in hand is still known exactly, call by call.
 */
final class Handlers {
    private Handlers() {
    }

    /** How long between two warnings about failing handlers. */
    private static long warned;

    static ItemStack insert(IItemHandler handler, int slot, ItemStack stack, boolean simulate) {
        try {
            return handler.insertItem(slot, stack, simulate);
        } catch (RuntimeException e) {
            warn(e);
            return stack;
        }
    }

    static ItemStack extract(IItemHandler handler, int slot, int amount, boolean simulate) {
        try {
            return handler.extractItem(slot, amount, simulate);
        } catch (RuntimeException e) {
            warn(e);
            return ItemStack.EMPTY;
        }
    }

    /** A fluid, energy or chemical amount moved by another mod's handler: none if the call fails. */
    static long amount(java.util.function.LongSupplier call) {
        try {
            return Math.max(0, call.getAsLong());
        } catch (RuntimeException e) {
            warn(e);
            return 0;
        }
    }

    static int slots(IItemHandler handler) {
        try {
            return handler.getSlots();
        } catch (RuntimeException e) {
            warn(e);
            return 0;
        }
    }

    static ItemStack stack(IItemHandler handler, int slot) {
        try {
            return handler.getStackInSlot(slot);
        } catch (RuntimeException e) {
            warn(e);
            return ItemStack.EMPTY;
        }
    }

    /** Into any slot of the handler, filling stacks of the same item first; returns what didn't go in. */
    static ItemStack insertAnywhere(IItemHandler handler, ItemStack stack) {
        ItemStack left = stack;
        for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
            for (int slot = 0; slot < slots(handler) && !left.isEmpty(); slot++) {
                ItemStack present = stack(handler, slot);
                if (pass == 0 ? !present.isEmpty() && ItemStack.isSameItemSameComponents(present, left) : present.isEmpty()) {
                    left = insert(handler, slot, left, false);
                }
            }
        }
        return left;
    }

    private static void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - warned > 60_000) {
            warned = now;
            Reticulum.LOGGER.warn("Another mod's item handler failed; the move was taken as not done", e);
        }
    }
}
