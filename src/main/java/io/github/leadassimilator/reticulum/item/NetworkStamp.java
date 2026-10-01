package io.github.leadassimilator.reticulum.item;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * A network written on an item stack: a linked network tool, a stack of connectors bound to a network, a controller
 * that remembers its network. The name rides along for tooltips (the client has no network list).
 */
public final class NetworkStamp {
    private NetworkStamp() {
    }

    public static void set(ItemStack stack, Network network) {
        stack.set(ModDataComponents.NETWORK_ID.get(), network.id());
        stack.set(ModDataComponents.NETWORK_NAME.get(), network.name());
    }

    public static void clear(ItemStack stack) {
        stack.remove(ModDataComponents.NETWORK_ID.get());
        stack.remove(ModDataComponents.NETWORK_NAME.get());
    }

    @Nullable
    public static UUID id(ItemStack stack) {
        return stack.get(ModDataComponents.NETWORK_ID.get());
    }

    public static boolean has(ItemStack stack) {
        return stack.has(ModDataComponents.NETWORK_ID.get());
    }
}
