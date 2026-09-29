package io.github.leadassimilator.reticulum.registry;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Reticulum.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.reticulum"))
            .icon(() -> ModItems.CONTROLLER.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(ModItems.CONTROLLER.get());
                output.accept(ModItems.CABLE.get());
                output.accept(ModItems.CONNECTOR.get());
                output.accept(ModItems.NETWORK_TOOL.get());
                output.accept(ModItems.ANTENNA_1.get());
                output.accept(ModItems.ANTENNA_2.get());
                output.accept(ModItems.ANTENNA_3.get());
                // A few facades to show what they are; any full block can be made into one.
                output.accept(io.github.leadassimilator.reticulum.item.FacadeItem.of(net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 1));
                output.accept(io.github.leadassimilator.reticulum.item.FacadeItem.of(net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(), 1));
                output.accept(io.github.leadassimilator.reticulum.item.FacadeItem.of(net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), 1));
            })
            .build());

    private ModCreativeTabs() {
    }
}
