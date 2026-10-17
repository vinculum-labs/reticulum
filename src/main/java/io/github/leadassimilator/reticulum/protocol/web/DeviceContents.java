package io.github.leadassimilator.reticulum.protocol.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.DeviceHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What a device holds right now, by id with totals, for the editor's filter picker ("in this device"). Reads every
 * side of the block (and no side). Sides often show the same inventory through different wrappers, so each id counts
 * the most any one handler holds rather than the sum.
 */
public final class DeviceContents {
    private DeviceContents() {
    }

    public static JsonObject read(ServerLevel level, BlockPos pos, String resource) {
        Map<Identifier, Long> totals = new LinkedHashMap<>();
        if (level.isLoaded(pos)) {
            switch (resource) {
                case "item" -> {
                    for (IItemHandler handler : handlers(level, pos, DeviceHandlers.ITEMS)) {
                        Map<Identifier, Long> one = new LinkedHashMap<>();
                        for (int slot = 0; slot < handler.getSlots(); slot++) {
                            ItemStack stack = handler.getStackInSlot(slot);
                            if (!stack.isEmpty()) {
                                one.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), (long) stack.getCount(), Long::sum);
                            }
                        }
                        keepLargest(totals, one);
                    }
                }
                case "fluid" -> {
                    for (IFluidHandler handler : handlers(level, pos, DeviceHandlers.FLUIDS)) {
                        Map<Identifier, Long> one = new LinkedHashMap<>();
                        for (int tank = 0; tank < handler.getTanks(); tank++) {
                            FluidStack stack = handler.getFluidInTank(tank);
                            if (!stack.isEmpty()) {
                                one.merge(BuiltInRegistries.FLUID.getKey(stack.getFluid()), (long) stack.getAmount(), Long::sum);
                            }
                        }
                        keepLargest(totals, one);
                    }
                }
                case "chemical" -> {
                    if (Mods.CHEMICALS != null) {
                        Mods.CHEMICALS.collect(level, pos, totals);
                    }
                }
                default -> {
                }
            }
        }
        JsonObject out = new JsonObject();
        JsonArray entries = new JsonArray();
        totals.forEach((id, amount) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id.toString());
            entry.addProperty("amount", amount);
            entries.add(entry);
        });
        out.add("entries", entries);
        return out;
    }

    /** Merges one handler's totals, keeping for each id the larger amount. */
    public static void keepLargest(Map<Identifier, Long> totals, Map<Identifier, Long> one) {
        one.forEach((id, amount) -> totals.merge(id, amount, Math::max));
    }

    /** The block's handlers of one kind on every side and no side, each distinct handler once. */
    public static <T> Set<T> handlers(ServerLevel level, BlockPos pos, DeviceHandlers<T> handlers) {
        Set<T> found = Collections.newSetFromMap(new IdentityHashMap<>());
        add(found, handlers.get(level, pos, null));
        for (Direction side : Direction.values()) {
            add(found, handlers.get(level, pos, side));
        }
        return found;
    }

    /** {@link #handlers} of a capability whose handlers are used as they are (Mekanism's chemicals). */
    public static <T> Set<T> handlers(ServerLevel level, BlockPos pos, BlockCapability<T, Direction> capability) {
        return handlers(level, pos, DeviceHandlers.direct(capability));
    }

    private static <T> void add(Set<T> found, @Nullable T handler) {
        if (handler != null) {
            found.add(handler);
        }
    }
}
