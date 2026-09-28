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

    public static void openNetworkPicker(io.github.leadassimilator.reticulum.protocol.Payloads.OpenNetworkPicker payload) {
        Minecraft.getInstance().setScreen(new io.github.leadassimilator.reticulum.client.screen.NetworkPickerScreen(payload.connector(), payload.choices()));
    }

    /** Sneak + scroll with the network tool in hand switches its mode instead of the hotbar slot. */
    public static void onScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !mc.player.isShiftKeyDown() || event.getScrollDeltaY() == 0
                || !(mc.player.getMainHandItem().getItem() instanceof io.github.leadassimilator.reticulum.item.NetworkToolItem)) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new io.github.leadassimilator.reticulum.protocol.Payloads.CycleToolMode(event.getScrollDeltaY() > 0 ? -1 : 1));
        event.setCanceled(true);
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
