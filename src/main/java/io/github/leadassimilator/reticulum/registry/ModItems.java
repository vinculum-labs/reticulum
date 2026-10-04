package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.AntennaItem;
import io.github.leadassimilator.reticulum.item.ConnectorItem;
import io.github.leadassimilator.reticulum.item.ControllerItem;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Reticulum.MODID);

    public static final DeferredItem<BlockItem> CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.CABLE);
    public static final DeferredItem<ConnectorItem> CONNECTOR = ITEMS.register("connector",
            () -> new ConnectorItem(ModBlocks.CONNECTOR.get(), new Item.Properties()));
    public static final DeferredItem<ControllerItem> CONTROLLER = ITEMS.register("controller",
            () -> new ControllerItem(ModBlocks.CONTROLLER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> STATUS_BEACON = ITEMS.registerSimpleBlockItem(ModBlocks.STATUS_BEACON);
    public static final DeferredItem<NetworkToolItem> NETWORK_TOOL = ITEMS.register("network_tool",
            () -> new NetworkToolItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<io.github.leadassimilator.reticulum.item.FacadeItem> FACADE = ITEMS.register("facade",
            () -> new io.github.leadassimilator.reticulum.item.FacadeItem(new Item.Properties()));
    public static final DeferredItem<AntennaItem> ANTENNA_1 = ITEMS.register("antenna_1", () -> new AntennaItem(1, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AntennaItem> ANTENNA_2 = ITEMS.register("antenna_2", () -> new AntennaItem(2, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AntennaItem> ANTENNA_3 = ITEMS.register("antenna_3", () -> new AntennaItem(3, new Item.Properties().stacksTo(1)));

    /** The antenna item of a tier, or empty for tier 0. */
    public static net.minecraft.world.item.ItemStack antenna(int tier) {
        return switch (tier) {
            case 1 -> new net.minecraft.world.item.ItemStack(ANTENNA_1.get());
            case 2 -> new net.minecraft.world.item.ItemStack(ANTENNA_2.get());
            case 3 -> new net.minecraft.world.item.ItemStack(ANTENNA_3.get());
            default -> net.minecraft.world.item.ItemStack.EMPTY;
        };
    }

    private ModItems() {
    }
}
