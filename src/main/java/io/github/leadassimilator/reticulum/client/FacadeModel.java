package io.github.leadassimilator.reticulum.client;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;

/**
 * Cables and connectors with a facade draw the facade's block instead of themselves: its parts, each with its own
 * render type, so glass stays see-through.
 */
public final class FacadeModel extends DelegateBlockStateModel {
    private FacadeModel(BlockStateModel original) {
        super(original);
    }

    /** Wraps the model of every cable and connector state. */
    public static void wrap(ModelEvent.ModifyBakingResult event) {
        Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
        for (Block block : new Block[] {ModBlocks.CABLE.get(), ModBlocks.CONNECTOR.get()}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                models.computeIfPresent(state, (key, model) -> new FacadeModel(model));
            }
        }
    }

    /** The facade at a position (from its block entity's model data), or null. */
    @Nullable
    private static BlockState facade(BlockAndTintGetter level, BlockPos pos) {
        return level.getModelData(pos).get(NetworkBlockEntity.FACADE);
    }

    private static BlockStateModel model(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockModelPart> parts) {
        BlockState facade = facade(level, pos);
        if (facade == null) {
            super.collectParts(level, pos, state, random, parts);
        } else {
            model(facade).collectParts(level, pos, facade, random, parts);
        }
    }

    @Override
    public TextureAtlasSprite particleIcon(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        BlockState facade = facade(level, pos);
        return facade == null ? super.particleIcon(level, pos, state) : model(facade).particleIcon(level, pos, facade);
    }
}
