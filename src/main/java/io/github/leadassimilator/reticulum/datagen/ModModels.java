package io.github.leadassimilator.reticulum.datagen;

import java.util.Map;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.ControllerBlock;
import io.github.leadassimilator.reticulum.block.LinkedBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.client.FacadeItemModel;
import io.github.leadassimilator.reticulum.client.ToolItemProperties;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.renderer.block.model.VariantMutator;
import net.minecraft.client.renderer.item.ItemModel;
import com.mojang.math.Quadrant;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** Block states, the generated block and item models, and every item's definition (what model it shows, and when). */
public class ModModels extends ModelProvider {
    public ModModels(PackOutput output) {
        super(output, Reticulum.MODID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blocks, ItemModelGenerators items) {
        // The Status Beacon: one model, its lens tinted by the look it shows (see BeaconEffects' block colors); the item
        // shows the lens gray.
        blocks.blockStateOutput.accept(MultiVariantGenerator.dispatch(ModBlocks.STATUS_BEACON.get(), model("block/status_beacon")));
        items.itemModelOutput.accept(ModItems.STATUS_BEACON.get(),
                ItemModelUtils.tintedModel(id("block/status_beacon"), ItemModelUtils.constantTint(0xFF585C66)));

        // The controller; with an antenna installed, its top shows that tier's antenna (like the item's icon), lying
        // with its base along the front edge. The top texture is drawn facing north, turned for the others.
        ResourceLocation plain = ModelTemplates.CUBE_ALL.create(id("block/controller"), TextureMapping.cube(id("block/controller")), blocks.modelOutput);
        ResourceLocation[] antenna = new ResourceLocation[4];
        for (int tier = 1; tier <= 3; tier++) {
            antenna[tier] = ModelTemplates.CUBE_TOP.create(id("block/controller_antenna_" + tier), new TextureMapping()
                    .put(TextureSlot.SIDE, id("block/controller")).put(TextureSlot.TOP, id("block/controller_top_antenna_" + tier)), blocks.modelOutput);
        }
        blocks.blockStateOutput.accept(MultiVariantGenerator.dispatch(ModBlocks.CONTROLLER.get()).with(
                PropertyDispatch.initial(ControllerBlock.ANTENNA, ControllerBlock.FACING).generate((tier, facing) ->
                        // toYRot counts from south; the texture is drawn for north (0°), so east 90°, south 180°, west 270°.
                        turned(BlockModelGenerators.plainVariant(tier == 0 ? plain : antenna[tier]), 0, ((int) facing.toYRot() + 180) % 360))));
        items.itemModelOutput.accept(ModItems.CONTROLLER.get(), ItemModelUtils.plainModel(plain));

        linked(blocks, ModBlocks.CABLE.get(), "cable_core", Map.of(Link.CABLE, "cable_arm"));
        linked(blocks, ModBlocks.CONNECTOR.get(), "connector_core", Map.of(Link.CABLE, "connector_arm", Link.DEVICE, "connector_plug"));
        items.itemModelOutput.accept(ModItems.CABLE.get(), ItemModelUtils.plainModel(id("block/cable_inventory")));
        items.itemModelOutput.accept(ModItems.CONNECTOR.get(), ItemModelUtils.plainModel(id("block/connector_inventory")));

        // The tool's head is colored by its mode (reticulum:mode, 0 Configure to 1 Status); an alarm going off adds a
        // yellow or red mark (reticulum:alarm, 0.5 a warning, 1 critical) to whichever mode.
        String[] modes = {"", "_link", "_identify", "_status"};
        float[] at = {0f, 0.33f, 0.66f, 1f};
        var byMode = new java.util.ArrayList<net.minecraft.client.renderer.item.RangeSelectItemModel.Entry>();
        for (int i = 0; i < modes.length; i++) {
            ItemModel.Unbaked look = ItemModelUtils.rangeSelect(new ToolItemProperties.Alarm(), flat(items, "network_tool" + modes[i]),
                    ItemModelUtils.override(flat(items, "network_tool" + modes[i] + "_warning"), 0.5f),
                    ItemModelUtils.override(flat(items, "network_tool" + modes[i] + "_critical"), 1f));
            byMode.add(ItemModelUtils.override(look, at[i]));
        }
        items.itemModelOutput.accept(ModItems.NETWORK_TOOL.get(), ItemModelUtils.rangeSelect(new ToolItemProperties.Mode(), byMode));

        // A painted facade shows its block (FacadeItemModel); a blank one its own texture.
        items.itemModelOutput.accept(ModItems.FACADE.get(), new FacadeItemModel.Unbaked(ItemModelUtils.plainModel(
                items.createFlatItemModel(ModItems.FACADE.get(), ModelTemplates.FLAT_ITEM))));
        for (Item antennaItem : new Item[] {ModItems.ANTENNA_1.get(), ModItems.ANTENNA_2.get(), ModItems.ANTENNA_3.get()}) {
            items.generateFlatItem(antennaItem, ModelTemplates.FLAT_ITEM);
        }
    }

    /** A flat item model with its own texture, item/{@code name}. */
    private static ItemModel.Unbaked flat(ItemModelGenerators items, String name) {
        return ItemModelUtils.plainModel(ModelTemplates.FLAT_ITEM.create(id("item/" + name), TextureMapping.layer0(id("item/" + name)), items.modelOutput));
    }

    /** A cable or connector: its core, plus a part turned to each side for what that side links to. */
    private static void linked(BlockModelGenerators blocks, LinkedBlock block, String core, Map<Link, String> parts) {
        MultiPartGenerator generator = MultiPartGenerator.multiPart(block).with(model("block/" + core));
        // Sides and parts in enum order, so the generated file comes out the same every run (the maps have no fixed order).
        for (Direction side : Direction.values()) {
            EnumProperty<Link> property = block.linkProperties().get(side);
            if (property == null) {
                continue;
            }
            for (Link link : Link.values()) {
                String model = parts.get(link);
                if (model != null) {
                    generator.with(BlockModelGenerators.condition().term(property, link), turned(model("block/" + model), switch (side) {
                        case UP -> 270;
                        case DOWN -> 90;
                        default -> 0;
                    }, switch (side) {
                        case EAST -> 90;
                        case SOUTH -> 180;
                        case WEST -> 270;
                        default -> 0;
                    }));
                }
            }
        }
        blocks.blockStateOutput.accept(generator);
    }

    private static MultiVariant model(String path) {
        return BlockModelGenerators.plainVariant(id(path));
    }

    /** Turned by {@code x} and {@code y} degrees (multiples of 90); a turn of 0 is left out of the file. */
    private static MultiVariant turned(MultiVariant variant, int x, int y) {
        if (x != 0) {
            variant = variant.with(VariantMutator.X_ROT.withValue(quadrant(x)));
        }
        if (y != 0) {
            variant = variant.with(VariantMutator.Y_ROT.withValue(quadrant(y)));
        }
        return variant;
    }

    private static Quadrant quadrant(int degrees) {
        return switch (degrees) {
            case 90 -> Quadrant.R90;
            case 180 -> Quadrant.R180;
            case 270 -> Quadrant.R270;
            default -> Quadrant.R0;
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, path);
    }
}
