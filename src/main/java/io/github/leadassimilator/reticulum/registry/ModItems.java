package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.item.AntennaItem;
import io.github.leadassimilator.reticulum.item.ConnectorItem;
import io.github.leadassimilator.reticulum.item.ControllerItem;
import io.github.leadassimilator.reticulum.item.FacadeItem;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Reticulum.MODID);

    public static final DeferredItem<BlockItem> CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.CABLE);
    public static final DeferredItem<ConnectorItem> CONNECTOR = ITEMS.registerItem("connector",
            properties -> new ConnectorItem(ModBlocks.CONNECTOR.get(), properties), properties -> properties.useBlockDescriptionPrefix());
    public static final DeferredItem<ControllerItem> CONTROLLER = ITEMS.registerItem("controller",
            properties -> new ControllerItem(ModBlocks.CONTROLLER.get(), properties), properties -> properties.useBlockDescriptionPrefix());
    public static final DeferredItem<BlockItem> STATUS_BEACON = ITEMS.registerSimpleBlockItem(ModBlocks.STATUS_BEACON);
    public static final DeferredItem<NetworkToolItem> NETWORK_TOOL = ITEMS.registerItem("network_tool",
            NetworkToolItem::new, properties -> properties.stacksTo(1));
    public static final DeferredItem<FacadeItem> FACADE = ITEMS.registerItem("facade",
            FacadeItem::new);
    public static final DeferredItem<AntennaItem> ANTENNA_1 = ITEMS.registerItem("antenna_1", properties -> new AntennaItem(1, properties), properties -> properties.stacksTo(1));
    public static final DeferredItem<AntennaItem> ANTENNA_2 = ITEMS.registerItem("antenna_2", properties -> new AntennaItem(2, properties), properties -> properties.stacksTo(1));
    public static final DeferredItem<AntennaItem> ANTENNA_3 = ITEMS.registerItem("antenna_3", properties -> new AntennaItem(3, properties), properties -> properties.stacksTo(1));

    /** The antenna item of a tier, or empty for tier 0. */
    public static ItemStack antenna(int tier) {
        return switch (tier) {
            case 1 -> new ItemStack(ANTENNA_1.get());
            case 2 -> new ItemStack(ANTENNA_2.get());
            case 3 -> new ItemStack(ANTENNA_3.get());
            default -> ItemStack.EMPTY;
        };
    }

    private ModItems() {
    }
}
