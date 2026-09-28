package io.github.leadassimilator.reticulum.network;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.integration.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Decides which neighboring blocks count as devices, and how they are named by default. */
public final class Devices {
    private static final BlockCapability<?, Direction>[] CAPABILITIES = new BlockCapability[] {
            Capabilities.ItemHandler.BLOCK,
            Capabilities.FluidHandler.BLOCK,
            Capabilities.EnergyStorage.BLOCK,
    };

    private Devices() {
    }

    /**
     * A device is any non-network block with a block entity or a supported capability on some face.
     */
    public static boolean isDevice(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        var be = level.getBlockEntity(pos);
        if (be instanceof NetworkBlockEntity) {
            return false;
        }
        if (be != null) {
            return true;
        }
        BlockCapability<?, Direction> chemicals = Mods.chemicalCapability();
        if (chemicals != null && offers(level, pos, chemicals)) {
            return true;
        }
        for (BlockCapability<?, Direction> cap : CAPABILITIES) {
            if (offers(level, pos, cap)) {
                return true;
            }
        }
        return false;
    }

    private static boolean offers(Level level, BlockPos pos, BlockCapability<?, Direction> cap) {
        if (level.getCapability(cap, pos, null) != null) {
            return true;
        }
        for (Direction side : Direction.values()) {
            if (level.getCapability(cap, pos, side) != null) {
                return true;
            }
        }
        return false;
    }

    public static ResourceLocation blockId(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
    }

    public static ItemStack icon(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        return stack;
    }

    /** "Crusher (12, 64, -3)": the block's name plus its position. */
    public static String defaultName(Level level, BlockPos pos) {
        return defaultName(level.getBlockState(pos).getBlock().getName(), pos);
    }

    public static String defaultName(Component blockName, BlockPos pos) {
        return blockName.getString() + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public static String displayName(@Nullable DeviceRecord record, Level level, BlockPos pos) {
        if (record != null && record.name() != null) {
            return record.name();
        }
        return defaultName(level, pos);
    }

    public static GlobalPos global(Level level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos.immutable());
    }
}
