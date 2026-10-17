package io.github.leadassimilator.reticulum.integration.jade;

import java.util.List;

import com.mojang.serialization.Codec;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.lookat.LookAt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade finds this class by its annotation, so it is only ever loaded when Jade is installed. The server works out the
 * lines (it knows the network); the client shows them.
 */
@WailaPlugin
public class ReticulumJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "network_info");
    private static final String KEY = "reticulum_lines";
    private static final Codec<List<Component>> LINES = ComponentSerialization.CODEC.listOf();

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(Lines.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(Tooltip.INSTANCE, Block.class);
    }

    /** On the server: the lines for the block looked at. */
    private enum Lines implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            // Holding the network tool, the lines show under the crosshair (for every block): not here as well.
            if (!(accessor.getLevel() instanceof ServerLevel level) || !NetworkToolItem.held(accessor.getPlayer()).isEmpty()) {
                return;
            }
            List<Component> lines = LookAt.describe(level, accessor.getPosition(), accessor.getSide(), accessor.getPlayer());
            if (!lines.isEmpty()) {
                data.store(KEY, LINES, level.registryAccess().createSerializationContext(NbtOps.INSTANCE), lines);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }

    /** On the client: the lines the server sent. */
    private enum Tooltip implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            List<Component> lines = accessor.getServerData()
                    .read(KEY, LINES, accessor.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE)).orElse(List.of());
            for (Component line : lines) {
                tooltip.add(line);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}
