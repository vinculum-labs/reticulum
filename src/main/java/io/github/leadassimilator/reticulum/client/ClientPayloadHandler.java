package io.github.leadassimilator.reticulum.client;

import io.github.leadassimilator.reticulum.client.screen.BlockScreen;
import io.github.leadassimilator.reticulum.client.screen.ControllerScreen;
import io.github.leadassimilator.reticulum.client.screen.DeviceFacesScreen;
import io.github.leadassimilator.reticulum.client.screen.NetworkPickerScreen;
import io.github.leadassimilator.reticulum.client.screen.QuickScreen;
import io.github.leadassimilator.reticulum.client.screen.StatusBeaconScreen;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.Views;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-only handling of server-to-client payloads. Only referenced from lambdas so servers never load it. */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    /**
     * Opens a block's screen, or updates it when it is open. A refresh (the server's answer to a change made in the
     * screen) never reopens it: Done may already have closed it, and the answer arriving just after would bring it back.
     */
    public static void openBlockScreen(Views.BlockView view, boolean refresh) {
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
        if (!refresh && mayReplace(mc)) {
            mc.setScreen(new BlockScreen(view));
        }
    }

    public static void openBeaconScreen(Payloads.OpenBeaconScreen payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StatusBeaconScreen open && open.pos().equals(payload.pos())) {
            open.refresh(payload);
        } else if (!payload.refresh() && mayReplace(mc)) {
            mc.setScreen(new StatusBeaconScreen(payload));
        }
    }

    public static void openNetworkPicker(Payloads.OpenNetworkPicker payload) {
        if (mayReplace(Minecraft.getInstance())) {
            Minecraft.getInstance().setScreen(new NetworkPickerScreen(payload.connector(), payload.choices()));
        }
    }

    /** Sneak + scroll with the network tool in hand switches its mode instead of the hotbar slot. */
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !mc.player.isShiftKeyDown() || event.getScrollDeltaY() == 0
                || !(mc.player.getMainHandItem().getItem() instanceof NetworkToolItem)) {
            return;
        }
        ClientPacketDistributor.sendToServer(
                new Payloads.CycleToolMode(event.getScrollDeltaY() > 0 ? -1 : 1));
        event.setCanceled(true);
    }

    /** As {@link #openBlockScreen}: a refresh updates the screen if open and never reopens it. */
    public static void openControllerScreen(Views.ControllerView view, boolean refresh) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ControllerScreen screen && screen.pos().equals(view.pos())) {
            screen.update(view);
            return;
        }
        if (!refresh && mayReplace(mc)) {
            mc.setScreen(new ControllerScreen(view));
        }
    }

    /**
     * A screen the server opens (in answer to a click, a moment later) replaces nothing but our own screens: not the
     * inventory or chat the player opened meanwhile.
     */
    private static boolean mayReplace(Minecraft mc) {
        return mc.screen == null || mc.screen instanceof QuickScreen;
    }
}
