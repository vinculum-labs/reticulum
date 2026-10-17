package io.github.leadassimilator.reticulum.item;

import java.util.List;
import java.util.UUID;

import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Controller item; remembers the network of the controller it was broken from. */
public class ControllerItem extends BlockItem {
    public ControllerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
            java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        Integer antenna = stack.get(ModDataComponents.ANTENNA_TIER.get());
        if (antenna != null) {
            tooltip.accept(AntennaItem.range(antenna).copy().withStyle(ChatFormatting.GRAY));
        }
        Long energy = stack.get(ModDataComponents.STORED_ENERGY.get());
        if (energy != null && energy > 0) {
            tooltip.accept(Component.translatable("tooltip.reticulum.stored_energy", String.format("%,d", energy)).withStyle(ChatFormatting.GRAY));
        }
        UUID network = stack.get(ModDataComponents.NETWORK_ID.get());
        if (network != null) {
            String name = stack.get(ModDataComponents.NETWORK_NAME.get());
            tooltip.accept(Component.translatable("tooltip.reticulum.remembers_network", name != null ? name : network.toString().substring(0, 8))
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
