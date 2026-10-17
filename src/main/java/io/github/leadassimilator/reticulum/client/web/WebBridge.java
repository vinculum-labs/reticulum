package io.github.leadassimilator.reticulum.client.web;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.telispex.client.Icons;
import io.github.leadassimilator.telispex.client.Page;
import io.github.leadassimilator.telispex.client.PageListener;
import io.github.leadassimilator.telispex.client.WebApp;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * The editor as a Telispex app: its page (built from web/), the catalog the filter picker reads, chemical icons, and
 * what the game client answers itself (which network to show, and which networks its pages show). Every other request
 * goes to the server, which checks each one against the player's permissions.
 */
public final class WebBridge {
    @Nullable
    private static WebApp app;
    /** The network the editor was last opened on, which a page that connects shows. */
    @Nullable
    private static UUID focus;

    private WebBridge() {
    }

    public static void init() {
        app = WebApp.builder(Reticulum.MODID, Component.translatable("screen.reticulum.editor"), WebBridge.class, "/assets/reticulum/web/")
                .devRoot("reticulum.webRoot")
                .loginHint("Open the Reticulum editor from the game: the controller's Open editor button.")
                .route("/catalog.json", "application/json; charset=utf-8", query -> Catalog.build())
                .pages(new Pages())
                .openInBrowser(a -> openInBrowser())
                .register();
        Icons.register("chemical", id -> {
            var chemicals = Mods.CHEMICALS;
            var icon = chemicals == null ? null : chemicals.icon(id);
            return icon == null ? Icons.transparent() : Icons.sprite(id, icon.texture(), icon.tint());
        });
    }

    private static WebApp app() {
        if (app == null) {
            throw new IllegalStateException("The editor isn't registered yet");
        }
        return app;
    }

    // ---------------------------------------------------------------- opening the editor

    /**
     * Shows the editor on a network: inside the game (when enabled and the system's web view works), else in the
     * browser. If a page is already connected it just switches to the network there.
     */
    public static void openEditor(@Nullable UUID network) {
        WebApp app = app();
        if (!app.start()) {
            return;
        }
        focus = network;
        if (app.inGameAvailable()) {
            if (app.viewLoaded()) {
                // The page is loaded (hidden); show it again, on this network.
                broadcastFocus(network);
                app.showInGame(null);
            } else {
                app.showInGame(app.link(fragment(network, true), true));
            }
            return;
        }
        openInBrowser();
    }

    /**
     * The link's fragment: the network to open on, and {@code embedded} marking the page as shown inside the game, so
     * Esc at its root closes it (the page remembers that across reloads).
     */
    private static String fragment(@Nullable UUID network, boolean embedded) {
        return (network == null ? "" : "network=" + network) + (embedded ? (network == null ? "" : "&") + "embedded=1" : "");
    }

    private static void broadcastFocus(@Nullable UUID network) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "focus");
        if (network != null) {
            message.addProperty("network", network.toString());
        }
        app().broadcast(message.toString());
    }

    /**
     * Shows the editor in the browser, on the network last asked for. If a browser page is already connected it just
     * switches tabs there; otherwise the browser is opened. The link is also put in chat for players who prefer to
     * open it themselves.
     */
    public static void openInBrowser() {
        WebApp app = app();
        if (!app.start()) {
            return;
        }
        UUID network = focus;
        if (app.browserPages() > 0) {
            broadcastFocus(network);
            chat(Component.translatable("message.reticulum.editor_focused"));
            return;
        }
        String url = app.link(fragment(network, false), false);
        app.browse(url);
        chat(Component.translatable("message.reticulum.editor_opened", Component.literal("[" + url.substring(0, url.indexOf("?")) + "]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("message.reticulum.editor_link_hover"))))));
    }

    private static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, false);
        }
    }

    // ---------------------------------------------------------------- pages

    /**
     * {@code hello} is answered here (whether there is a world, and the network to show). The networks each page shows
     * are tracked: the server keeps one subscription per player, not per page, so a page letting go of a network another
     * page still shows mustn't unsubscribe it for both; that is only sent for the last.
     */
    private static final class Pages implements PageListener {
        private final Map<Page, Set<String>> shown = new HashMap<>();

        @Override
        public boolean onRequest(Page page, String op, JsonObject request) {
            String id = request.has("id") && request.get("id").isJsonPrimitive() ? request.get("id").getAsString() : null;
            if (op.equals("hello")) {
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("connected", Minecraft.getInstance().getConnection() != null);
                if (focus != null) {
                    hello.addProperty("focus", focus.toString());
                }
                page.send(hello);
                return true;
            }
            String network = request.has("network") && request.get("network").isJsonPrimitive() ? request.get("network").getAsString() : null;
            if (op.equals("subscribe") && network != null) {
                synchronized (shown) {
                    shown.computeIfAbsent(page, k -> new HashSet<>()).add(network);
                }
            } else if (op.equals("unsubscribe") && network != null && !letGo(page, network)) {
                // Still shown on another page: only this page lets go.
                page.reply(id);
                return true;
            }
            return false;
        }

        /** A page closed: the networks no other page shows are unsubscribed. */
        @Override
        public void onClose(Page page) {
            Set<String> networks;
            synchronized (shown) {
                networks = shown.containsKey(page) ? Set.copyOf(shown.get(page)) : Set.of();
            }
            for (String network : networks) {
                if (letGo(page, network)) {
                    JsonObject request = new JsonObject();
                    request.addProperty("op", "unsubscribe");
                    request.addProperty("network", network);
                    Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().getConnection() != null) {
                            page.app().toServer(request.toString());
                        }
                    });
                }
            }
            synchronized (shown) {
                shown.remove(page);
            }
        }

        /** This page stops showing a network; true when no other page shows it (so the server can stop sending it). */
        private boolean letGo(Page page, String network) {
            synchronized (shown) {
                Set<String> mine = shown.get(page);
                if (mine != null) {
                    mine.remove(network);
                }
                return shown.entrySet().stream().noneMatch(e -> e.getKey() != page && e.getValue().contains(network));
            }
        }
    }
}
