package io.github.leadassimilator.reticulum.integration.jade;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.lookat.LookAt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
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

/** Jade finds this class by its annotation, so it is only ever loaded when Jade is installed. */
@WailaPlugin
public class ReticulumJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "network_info");
    private static final String KEY = "reticulum_lines";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(Provider.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(Provider.INSTANCE, Block.class);
    }

    private enum Provider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel level)) {
                return;
            }
            ListTag lines = new ListTag();
            for (Component line : LookAt.describe(level, accessor.getPosition(), accessor.getSide(), accessor.getPlayer())) {
                lines.add(StringTag.valueOf(Component.Serializer.toJson(line, level.registryAccess())));
            }
            if (!lines.isEmpty()) {
                data.put(KEY, lines);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            ListTag lines = accessor.getServerData().getList(KEY, Tag.TAG_STRING);
            if (!lines.isEmpty()) {
                // Our own HUD would say the same under the crosshair.
                io.github.leadassimilator.reticulum.client.LookHud.coveredByJade(accessor.getPosition());
            }
            for (int i = 0; i < lines.size(); i++) {
                Component line = Component.Serializer.fromJson(lines.getString(i), accessor.getLevel().registryAccess());
                if (line != null) {
                    tooltip.add(line);
                }
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}
