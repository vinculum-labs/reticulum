package io.github.leadassimilator.reticulum.item;

import java.util.List;
import java.util.function.Consumer;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * An antenna upgrade for a controller: right-click the controller with it. It sets how far that network's wireless
 * connectors (those no cable joins to the controller) may be.
 */
public class AntennaItem extends Item {
    private final int tier;

    public AntennaItem(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(range(tier).copy().withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.reticulum.antenna_use").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** "Wireless range: 128 blocks, same dimension" and the like. */
    public static Component range(int tier) {
        return switch (tier) {
            case 0 -> Component.translatable("tooltip.reticulum.antenna_none");
            case 1 -> Component.translatable("tooltip.reticulum.antenna_1", ReticulumConfig.ANTENNA_RANGE.getAsInt());
            case 2 -> Component.translatable("tooltip.reticulum.antenna_2");
            default -> Component.translatable("tooltip.reticulum.antenna_3");
        };
    }
}
