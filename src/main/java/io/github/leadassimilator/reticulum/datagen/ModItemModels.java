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
    private static final net.minecraft.resources.ResourceLocation ALARM = io.github.leadassimilator.reticulum.item.NetworkToolItem.ALARM_PROPERTY;

    @Override
    protected void registerModels() {
        withExistingParent(ModItems.CABLE.getId().getPath(), modLoc("block/cable_inventory"));
        withExistingParent(ModItems.CONNECTOR.getId().getPath(), modLoc("block/connector_inventory"));
        withExistingParent(ModItems.CONTROLLER.getId().getPath(), modLoc("block/controller"));
        withExistingParent(ModItems.STATUS_BEACON.getId().getPath(), modLoc("block/status_beacon_none"));
        // The tool's head is colored by its mode (the "reticulum:mode" item property: 0 Configure, 0.5 Link, 1 Identify).
        // An alarm going off adds a yellow or red mark ("reticulum:alarm": 0.5 a warning, 1 critical) to whichever mode.
        String[] modes = {"", "_link", "_identify", "_status"};
        float[] at = {0f, 0.33f, 0.66f, 1f};
        for (String mode : modes) {
            if (!mode.isEmpty()) {
                basicItem(modLoc("network_tool" + mode));
            }
            basicItem(modLoc("network_tool" + mode + "_warning"));
            basicItem(modLoc("network_tool" + mode + "_critical"));
        }
        var tool = basicItem(ModItems.NETWORK_TOOL.get());
        // Overrides are read in order and the last that matches wins, so each mode comes after the ones below it.
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) {
                tool.override().predicate(TOOL_MODE, at[i]).model(getExistingFile(modLoc("item/network_tool" + modes[i]))).end();
            }
            tool.override().predicate(TOOL_MODE, at[i]).predicate(ALARM, 0.5f).model(getExistingFile(modLoc("item/network_tool" + modes[i] + "_warning"))).end();
            tool.override().predicate(TOOL_MODE, at[i]).predicate(ALARM, 1f).model(getExistingFile(modLoc("item/network_tool" + modes[i] + "_critical"))).end();
        }
        basicItem(ModItems.FACADE.get());
        basicItem(ModItems.ANTENNA_1.get());
        basicItem(ModItems.ANTENNA_2.get());
        basicItem(ModItems.ANTENNA_3.get());
    }
}
