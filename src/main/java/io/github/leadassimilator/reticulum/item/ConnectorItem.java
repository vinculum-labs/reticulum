package io.github.leadassimilator.reticulum.item;

import java.util.List;

import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * Connector item. A stack bound at a controller (right-click the controller with it) joins that network when placed
 * where no cable decides otherwise: a wireless connector.
 */
public class ConnectorItem extends BlockItem {
    public ConnectorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
            java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        if (stack.has(ModDataComponents.NETWORK_ID.get())) {
            String name = stack.getOrDefault(ModDataComponents.NETWORK_NAME.get(), "?");
            tooltip.accept(Component.translatable("tooltip.reticulum.bound_to", name).withStyle(ChatFormatting.GRAY));
        }
    }
}
