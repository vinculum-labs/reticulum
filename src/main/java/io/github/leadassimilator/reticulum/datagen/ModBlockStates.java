package io.github.leadassimilator.reticulum.datagen;

import java.util.Map;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
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
        simpleBlock(ModBlocks.CONTROLLER.get(), models().cubeAll("controller", modLoc("block/controller")));

        linked(ModBlocks.CABLE.get(), "cable_core", Map.of(Link.CABLE, "cable_arm"));
        linked(ModBlocks.CONNECTOR.get(), "connector_core",
                Map.of(Link.CABLE, "connector_arm", Link.DEVICE, "connector_plug"));
    }

    private void linked(LinkedBlock block, String core, Map<Link, String> parts) {
        MultiPartBlockStateBuilder builder = getMultipartBuilder(block);
        builder.part().modelFile(existing(core)).addModel().end();
        for (Map.Entry<Direction, EnumProperty<Link>> side : block.linkProperties().entrySet()) {
            for (Map.Entry<Link, String> part : parts.entrySet()) {
                builder.part().modelFile(existing(part.getValue()))
                        .rotationX(rotationX(side.getKey())).rotationY(rotationY(side.getKey())).addModel()
                        .condition(side.getValue(), part.getKey()).end();
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
