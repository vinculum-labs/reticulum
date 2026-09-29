package io.github.leadassimilator.reticulum.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Key handling shared by the quick screens. */
final class ScreenKeys {
    private ScreenKeys() {
    }

    /**
     * The inventory key closes the screen, as it does for container screens (and Mekanism's machine screens),
     * unless a text box has focus, where the key is just a letter.
     */
    static boolean isInventoryKey(Screen screen, int keyCode, int scanCode) {
        if (screen.getFocused() instanceof EditBox box && box.isFocused()) {
            return false;
        }
        return Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode);
    }
}
