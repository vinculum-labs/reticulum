package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.CableBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Reticulum.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CableBlockEntity>> CABLE = BLOCK_ENTITIES.register("cable",
            () -> new BlockEntityType<>(CableBlockEntity::new, ModBlocks.CABLE.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectorBlockEntity>> CONNECTOR = BLOCK_ENTITIES.register("connector",
            () -> new BlockEntityType<>(ConnectorBlockEntity::new, ModBlocks.CONNECTOR.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControllerBlockEntity>> CONTROLLER = BLOCK_ENTITIES.register("controller",
            () -> new BlockEntityType<>(ControllerBlockEntity::new, ModBlocks.CONTROLLER.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StatusBeaconBlockEntity>> STATUS_BEACON =
            BLOCK_ENTITIES.register("status_beacon", () -> new BlockEntityType<>(StatusBeaconBlockEntity::new,
                    ModBlocks.STATUS_BEACON.get()));

    private ModBlockEntities() {
    }
}
