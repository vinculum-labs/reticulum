package io.github.leadassimilator.reticulum.registry;

import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import com.mojang.serialization.Codec;

import io.github.leadassimilator.reticulum.item.ToolMode;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.leadassimilator.reticulum.network.Ids;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Reticulum.MODID);

    /** The network a controller item belongs to, so breaking and replacing a controller keeps its design. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> NETWORK_ID = COMPONENTS.registerComponentType("network_id",
            b -> b.persistent(Ids.UUID_CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** The name of the network in {@link #NETWORK_ID}, for tooltips (the client has no network list). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> NETWORK_NAME = COMPONENTS.registerComponentType("network_name",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** The block a facade item makes a cable or connector look like. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.world.level.block.state.BlockState>> FACADE =
            COMPONENTS.registerComponentType("facade", b -> b.persistent(net.minecraft.world.level.block.state.BlockState.CODEC)
                    .networkSynchronized(ByteBufCodecs.idMapper(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY)));

    /** The network tool's mode. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ToolMode>> TOOL_MODE = COMPONENTS.registerComponentType("tool_mode",
            b -> b.persistent(ToolMode.CODEC).networkSynchronized(ToolMode.STREAM_CODEC));

    private ModDataComponents() {
    }
}
