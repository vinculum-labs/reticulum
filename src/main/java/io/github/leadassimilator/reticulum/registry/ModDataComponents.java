package io.github.leadassimilator.reticulum.registry;

import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Reticulum.MODID);

    /** The network a controller item belongs to, so breaking and replacing a controller keeps its design. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> NETWORK_ID = COMPONENTS.registerComponentType("network_id",
            b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    private ModDataComponents() {
    }
}
