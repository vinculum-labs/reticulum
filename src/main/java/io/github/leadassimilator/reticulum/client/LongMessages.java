package io.github.leadassimilator.reticulum.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/**
 * Our messages above the hotbar are one line that never wraps: one too long for the screen (at its GUI scale) was cut
 * off at both ends. Those go to chat instead, where they wrap and can be read again.
 */
public final class LongMessages {
    /** Room kept on either side of the action bar text. */
    private static final int MARGIN = 20;

    private LongMessages() {
    }

    public static void onSystemMessage(ClientChatReceivedEvent.System event) {
        if (!event.isOverlay() || !ours(event.getMessage())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.font.width(event.getMessage()) <= mc.getWindow().getGuiScaledWidth() - 2 * MARGIN) {
            return;
        }
        event.setCanceled(true);
        mc.gui.getChat().addClientSystemMessage(event.getMessage());
    }

    private static boolean ours(Component message) {
        return message.getContents() instanceof TranslatableContents translatable
                && (translatable.getKey().startsWith("message.reticulum.") || translatable.getKey().startsWith("command.reticulum."));
    }
}
