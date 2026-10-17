package io.github.leadassimilator.reticulum.datagen;

import java.util.Map;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Multipart block states for cables and connectors. The element models they point at (core, arm, plug) are
 * hand-made source art in src/main/resources, modeled facing north and rotated here for the other sides.
 */
public class ModBlockStates extends BlockStateProvider {
    public ModBlockStates(PackOutput output, ExistingFileHelper files) {
        super(output, Reticulum.MODID, files);
    }

    @Override
    protected void registerStatesAndModels() {
        // The Status Beacon: one model, its lens tinted by the look it shows (see ReticulumClient's block colors).
        ModelFile beacon = models().getExistingFile(modLoc("block/status_beacon"));
        getVariantBuilder(ModBlocks.STATUS_BEACON.get()).forAllStates(state ->
                ConfiguredModel.builder().modelFile(beacon).build());
        // The controller; with an antenna installed, its top shows that tier's antenna (like the item's icon), lying
        // with its base along the front edge. The top texture is drawn facing north, turned for the others.
        ModelFile plain = models().cubeAll("controller", modLoc("block/controller"));
        getVariantBuilder(ModBlocks.CONTROLLER.get()).forAllStates(state -> {
            int tier = state.getValue(ControllerBlock.ANTENNA);
            ModelFile model = tier == 0 ? plain
                    : models().cubeTop("controller_antenna_" + tier, modLoc("block/controller"), modLoc("block/controller_top_antenna_" + tier));
            return ConfiguredModel.builder().modelFile(model)
                    // toYRot counts from south; the texture is drawn for north (0°), so east 90°, south 180°, west 270°.
                    .rotationY(((int) state.getValue(ControllerBlock.FACING).toYRot() + 180) % 360)
                    .build();
        });

        linked(ModBlocks.CABLE.get(), "cable_core", Map.of(Link.CABLE, "cable_arm"));
        linked(ModBlocks.CONNECTOR.get(), "connector_core",
                Map.of(Link.CABLE, "connector_arm", Link.DEVICE, "connector_plug"));
    }

    private void linked(LinkedBlock block, String core, Map<Link, String> parts) {
        MultiPartBlockStateBuilder builder = getMultipartBuilder(block);
        builder.part().modelFile(existing(core)).addModel().end();
        // Sides and parts in enum order, so the generated file comes out the same every run (the maps have no fixed order).
        for (Direction side : Direction.values()) {
            EnumProperty<Link> property = block.linkProperties().get(side);
            if (property == null) {
                continue;
            }
            for (Link link : Link.values()) {
                String model = parts.get(link);
                if (model != null) {
                    builder.part().modelFile(existing(model)).rotationX(rotationX(side)).rotationY(rotationY(side)).addModel()
                            .condition(property, link).end();
                }
            }
        }
    }

    private ModelFile existing(String name) {
        return models().getExistingFile(modLoc("block/" + name));
    }

    private static int rotationX(Direction side) {
        return switch (side) {
            case UP -> 270;
            case DOWN -> 90;
            default -> 0;
        };
    }

    private static int rotationY(Direction side) {
        return switch (side) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }
}
