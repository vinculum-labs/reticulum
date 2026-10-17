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
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
        if (be != null || redstone(state)) {
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

    /**
     * Whether the block counts as a device for a connector on its {@code facing} side. AE2 blocks that offer nothing
     * on that side (a cable bus holding only a part, like a storage bus with no cable) don't: a connector can neither
     * move anything through them nor link to their AE2 network there.
     */
    public static boolean isDevice(Level level, BlockPos pos, Direction facing) {
        if (!isDevice(level, pos)) {
            return false;
        }
        return Mods.ME == null || !Mods.ME.offersNothing(level, pos, facing) || offersResource(level, pos, facing);
    }

    private static boolean offersResource(Level level, BlockPos pos, Direction facing) {
        BlockCapability<?, Direction> chemicals = Mods.chemicalCapability();
        if (chemicals != null && level.getCapability(chemicals, pos, facing) != null) {
            return true;
        }
        for (BlockCapability<?, Direction> cap : CAPABILITIES) {
            if (level.getCapability(cap, pos, facing) != null) {
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

    /**
     * Blocks that give out redstone (levers, sensors, dust), have a comparator value, or react to it (lamps, doors,
     * pistons, dispensers), so Redstone in and out nodes can use them.
     */
    private static boolean redstone(BlockState state) {
        return state.isSignalSource() || state.hasAnalogOutputSignal()
                || state.hasProperty(BlockStateProperties.POWERED) || state.hasProperty(BlockStateProperties.TRIGGERED)
                || state.hasProperty(BlockStateProperties.EXTENDED) || state.getBlock() instanceof RedstoneLampBlock;
    }

    /** Whether the block gives out redstone: a signal source (lever, sensor, dust) or a comparator value (a chest). */
    public static boolean givesRedstone(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isSignalSource() || state.hasAnalogOutputSignal();
    }

    /**
     * Whether the block reacts to redstone: it has a powered, triggered or extended state (doors, dispensers, pistons),
     * is a lamp, or is a machine (has a block entity). Signal sources don't (levers, sensors), except the ones that
     * carry a signal on: dust, repeaters and comparators, which run either way depending on how they are placed.
     */
    public static boolean takesRedstone(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isSignalSource()) {
            return state.getBlock() instanceof RedStoneWireBlock || state.getBlock() instanceof DiodeBlock;
        }
        return state.hasProperty(BlockStateProperties.POWERED) || state.hasProperty(BlockStateProperties.TRIGGERED)
                || state.hasProperty(BlockStateProperties.EXTENDED) || state.getBlock() instanceof RedstoneLampBlock
                || level.getBlockEntity(pos) != null;
    }

    public static ResourceLocation blockId(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
    }

    /**
     * The item that shows the device: what pick-block (middle-click) gives, so blocks without an item of their own,
     * like AE2's cable bus, show the cable or part in them. Falls back to the block's item.
     */
    public static ItemStack icon(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, true);
            ItemStack picked = state.getCloneItemStack(hit, level, pos, null);
            if (!picked.isEmpty()) {
                return picked;
            }
        } catch (RuntimeException e) {
            // Some blocks expect a player here; the block's item will do.
        }
        return new ItemStack(state.getBlock().asItem());
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

    /** The side of {@code from} that faces {@code to}, when they touch (null otherwise). */
    @Nullable
    public static Direction facing(BlockPos from, BlockPos to) {
        return Direction.fromDelta(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    public static GlobalPos global(Level level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos.immutable());
    }
}
