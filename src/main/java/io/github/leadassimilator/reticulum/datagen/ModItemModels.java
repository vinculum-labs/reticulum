package io.github.leadassimilator.reticulum.datagen;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModItemModels extends ItemModelProvider {
    public ModItemModels(PackOutput output, ExistingFileHelper files) {
        super(output, Reticulum.MODID, files);
    }

    private static final net.minecraft.resources.ResourceLocation TOOL_MODE = io.github.leadassimilator.reticulum.item.NetworkToolItem.MODE_PROPERTY;

    @Override
    protected void registerModels() {
        withExistingParent(ModItems.CABLE.getId().getPath(), modLoc("block/cable_inventory"));
        withExistingParent(ModItems.CONNECTOR.getId().getPath(), modLoc("block/connector_inventory"));
        withExistingParent(ModItems.CONTROLLER.getId().getPath(), modLoc("block/controller"));
        withExistingParent(ModItems.STATUS_BEACON.getId().getPath(), modLoc("block/status_beacon_none"));
        // The tool's head is colored by its mode (the "reticulum:mode" item property: 0 Configure, 0.5 Link, 1 Identify).
        basicItem(modLoc("network_tool_link"));
        basicItem(modLoc("network_tool_identify"));
        basicItem(ModItems.NETWORK_TOOL.get())
                .override().predicate(TOOL_MODE, 0.5f).model(getExistingFile(modLoc("item/network_tool_link"))).end()
                .override().predicate(TOOL_MODE, 1f).model(getExistingFile(modLoc("item/network_tool_identify"))).end();
        basicItem(ModItems.FACADE.get());
        basicItem(ModItems.ANTENNA_1.get());
        basicItem(ModItems.ANTENNA_2.get());
        basicItem(ModItems.ANTENNA_3.get());
    }
}
