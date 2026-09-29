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

    @Override
    protected void registerModels() {
        withExistingParent(ModItems.CABLE.getId().getPath(), modLoc("block/cable_inventory"));
        withExistingParent(ModItems.CONNECTOR.getId().getPath(), modLoc("block/connector_inventory"));
        withExistingParent(ModItems.CONTROLLER.getId().getPath(), modLoc("block/controller"));
        basicItem(ModItems.NETWORK_TOOL.get());
        basicItem(ModItems.FACADE.get());
        basicItem(ModItems.ANTENNA_1.get());
        basicItem(ModItems.ANTENNA_2.get());
        basicItem(ModItems.ANTENNA_3.get());
    }
}
