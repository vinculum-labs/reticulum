package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.CableBlock;
import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Reticulum.MODID);

    public static final DeferredBlock<CableBlock> CABLE = BLOCKS.register("cable", () -> new CableBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.5f).sound(SoundType.METAL).noOcclusion().dynamicShape()));
    public static final DeferredBlock<ConnectorBlock> CONNECTOR = BLOCKS.register("connector", () -> new ConnectorBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.0f).sound(SoundType.METAL).noOcclusion().dynamicShape()));
    public static final DeferredBlock<StatusBeaconBlock> STATUS_BEACON = BLOCKS.register("status_beacon",
            () -> new StatusBeaconBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5f)
                    .sound(SoundType.METAL).lightLevel(state -> state.getValue(StatusBeaconBlock.SHOWN) == 0 ? 0 : 15)));
    public static final DeferredBlock<ControllerBlock> CONTROLLER = BLOCKS.register("controller", () -> new ControllerBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL)));

    private ModBlocks() {
    }
}
