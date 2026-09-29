package io.github.leadassimilator.reticulum.client;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Cables and connectors with a facade draw the facade's block instead of themselves. */
public final class FacadeModel extends BakedModelWrapper<BakedModel> {
    private FacadeModel(BakedModel original) {
        super(original);
    }

    /** Wraps the model of every cable and connector state. */
    public static void wrap(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (Block block : new Block[] {ModBlocks.CABLE.get(), ModBlocks.CONNECTOR.get()}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                models.computeIfPresent(BlockModelShaper.stateToModelLocation(state), (location, model) -> new FacadeModel(model));
            }
        }
    }

    @Nullable
    private static BakedModel facadeModel(ModelData data) {
        BlockState facade = data.get(NetworkBlockEntity.FACADE);
        return facade == null ? null : Minecraft.getInstance().getBlockRenderer().getBlockModel(facade);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
            @Nullable RenderType renderType) {
        BlockState facade = data.get(NetworkBlockEntity.FACADE);
        if (facade == null) {
            return super.getQuads(state, side, rand, data, renderType);
        }
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(facade);
        if (renderType != null && !model.getRenderTypes(facade, rand, ModelData.EMPTY).contains(renderType)) {
            return List.of();
        }
        return model.getQuads(facade, side, rand, ModelData.EMPTY, renderType);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState facade = data.get(NetworkBlockEntity.FACADE);
        return facade == null ? super.getRenderTypes(state, rand, data)
                : Minecraft.getInstance().getBlockRenderer().getBlockModel(facade).getRenderTypes(facade, rand, ModelData.EMPTY);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        BakedModel facade = facadeModel(data);
        return facade == null ? super.getParticleIcon(data) : facade.getParticleIcon(ModelData.EMPTY);
    }
}
