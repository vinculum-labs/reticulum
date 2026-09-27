package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.ControllerItem;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Reticulum.MODID);

    public static final DeferredItem<BlockItem> CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.CABLE);
    public static final DeferredItem<BlockItem> CONNECTOR = ITEMS.registerSimpleBlockItem(ModBlocks.CONNECTOR);
    public static final DeferredItem<ControllerItem> CONTROLLER = ITEMS.register("controller",
            () -> new ControllerItem(ModBlocks.CONTROLLER.get(), new Item.Properties()));
    public static final DeferredItem<NetworkToolItem> NETWORK_TOOL = ITEMS.register("network_tool",
            () -> new NetworkToolItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
