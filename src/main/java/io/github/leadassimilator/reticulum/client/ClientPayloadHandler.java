package io.github.leadassimilator.reticulum.client;

import io.github.leadassimilator.reticulum.client.screen.BlockScreen;
import io.github.leadassimilator.reticulum.client.screen.ControllerScreen;
import io.github.leadassimilator.reticulum.client.screen.DeviceFacesScreen;
import io.github.leadassimilator.reticulum.protocol.Views;
import net.minecraft.client.Minecraft;

/** Client-only handling of server-to-client payloads. Only referenced from lambdas so servers never load it. */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void openBlockScreen(Views.BlockView view) {
        Minecraft mc = Minecraft.getInstance();
        // A refresh for the screen that is already open updates it in place instead of stacking a new one.
        if (mc.screen instanceof DeviceFacesScreen faces && faces.pos().equals(view.pos())) {
            faces.update(view);
            return;
        }
        if (mc.screen instanceof BlockScreen screen && screen.pos().equals(view.pos())) {
            screen.update(view);
            return;
        }
        mc.setScreen(new BlockScreen(view));
    }

    public static void openControllerScreen(Views.ControllerView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ControllerScreen screen && screen.pos().equals(view.pos())) {
            screen.update(view);
            return;
        }
        mc.setScreen(new ControllerScreen(view));
    }
}
