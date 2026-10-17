package io.github.leadassimilator.reticulum.network;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A device's item, fluid and energy handlers, as the handler interfaces the movers are written against. Since 1.21.9
 * NeoForge offers transfer handlers instead; each is wrapped once (kept while it lives), so a handler two faces share
 * is still one handler, and the runtime counts and moves it once.
 */
public final class DeviceHandlers<T> {
    public static final DeviceHandlers<IItemHandler> ITEMS = new DeviceHandlers<>(Capabilities.Item.BLOCK, IItemHandler::of);
    public static final DeviceHandlers<IFluidHandler> FLUIDS = new DeviceHandlers<>(Capabilities.Fluid.BLOCK, IFluidHandler::of);
    public static final DeviceHandlers<IEnergyStorage> ENERGY = new DeviceHandlers<>(Capabilities.Energy.BLOCK, IEnergyStorage::of);

    private final BlockCapability<?, Direction> capability;
    private final Function<Object, T> wrap;
    /** Wrappers by the handler they wrap; both are let go once nothing else holds the handler. */
    private final Map<Object, WeakReference<T>> wrapped = Collections.synchronizedMap(new WeakHashMap<>());

    @SuppressWarnings("unchecked")
    private <H> DeviceHandlers(BlockCapability<H, Direction> capability, Function<H, T> wrap) {
        this.capability = capability;
        this.wrap = handler -> wrap.apply((H) handler);
    }

    /** A capability whose handlers are used as they are (Mekanism's chemicals). */
    public static <T> DeviceHandlers<T> direct(BlockCapability<T, Direction> capability) {
        return new DeviceHandlers<>(capability, Function.identity());
    }

    /** The capability looked up (for capability caches, and to register a block's own handlers). */
    public BlockCapability<?, Direction> capability() {
        return capability;
    }

    @Nullable
    public T get(Level level, BlockPos pos, @Nullable Direction side) {
        return wrap(level.getCapability(capability, pos, side));
    }

    /** The wrapper of a handler the capability gave (the same one each time while the handler lives). */
    @Nullable
    public T wrap(@Nullable Object handler) {
        if (handler == null) {
            return null;
        }
        synchronized (wrapped) {
            WeakReference<T> known = wrapped.get(handler);
            T wrapper = known == null ? null : known.get();
            if (wrapper == null) {
                wrapper = wrap.apply(handler);
                wrapped.put(handler, new WeakReference<>(wrapper));
            }
            return wrapper;
        }
    }
}
