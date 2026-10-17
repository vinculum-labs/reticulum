package io.github.leadassimilator.telispex.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.leadassimilator.telispex.Telispex;
import io.github.leadassimilator.telispex.TelispexConfig;
import io.github.leadassimilator.telispex.client.view.EmbeddedView;
import io.github.leadassimilator.telispex.client.view.ViewScreen;
import io.github.leadassimilator.telispex.relay.RelayPayloads;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A mod's web page: its files, any routes it answers itself, and what its pages say over their WebSocket. Pages talk
 * JSON in the relay's envelope (see {@link io.github.leadassimilator.telispex.relay.Relay}); requests the app's
 * {@link PageListener} doesn't answer go to the server, to the handler the app registered there.
 *
 * <p>Besides the app's own, a page finds these paths beside it: {@code ws} (the WebSocket) and
 * {@code _/icon/<kind>/<namespace>/<path>.png} (see {@link Icons}). It should name them relative to itself (no leading
 * slash), as it may be served at {@code /<app>/} rather than at its own host's root. Pages are sent
 * {@code {"type": "connected"}} and {@code {"type": "disconnected"}} as the player joins and leaves a world, and may
 * ask for {@code closeView} (hide the in-game view showing them) and {@code toggleFullscreen}.
 */
public final class WebApp {
    private static final Map<String, WebApp> APPS = new ConcurrentHashMap<>();

    private final String id;
    private final Component title;
    private final Class<?> anchor;
    private final String assets;
    @Nullable
    private final String devRootProperty;
    private final String loginHint;
    private final Map<String, Answer> routes;
    private final PageListener listener;
    @Nullable
    private final Consumer<WebApp> openInBrowser;
    private final ChannelGroup sockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private WebApp(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.anchor = builder.anchor;
        this.assets = builder.assets;
        this.devRootProperty = builder.devRootProperty;
        this.loginHint = builder.loginHint;
        this.routes = Map.copyOf(builder.routes);
        this.listener = builder.listener;
        this.openInBrowser = builder.openInBrowser;
    }

    /**
     * Starts an app, usually named after the mod. {@code title} heads the in-game view. Its files are read with
     * {@code anchor}'s class loader from {@code assets} (a resource folder such as "/assets/mymod/web/").
     */
    public static Builder builder(String id, Component title, Class<?> anchor, String assets) {
        return new Builder(id, title, anchor, assets);
    }

    @Nullable
    static WebApp byId(String id) {
        return APPS.get(id);
    }

    static Collection<WebApp> all() {
        return APPS.values();
    }

    public String id() {
        return id;
    }

    public Component title() {
        return title;
    }

    // ---------------------------------------------------------------- showing the page

    /** Starts the web server if it isn't running. False when it couldn't (the log says why). */
    public boolean start() {
        return WebServer.start();
    }

    /**
     * A login link to the app's page, with {@code fragment} (without the #, "" for none) after it. {@code inGame}: for
     * the in-game view rather than a browser, which may reach the page by another host.
     */
    public String link(String fragment, boolean inGame) {
        return WebServer.origin(this, inGame) + "/?token=" + WebServer.token(this) + (fragment.isEmpty() ? "" : "#" + fragment);
    }

    /** Whether pages open in the game: the player wants them there and this system has a view for them. */
    public boolean inGameAvailable() {
        return TelispexConfig.IN_GAME.getAsBoolean() && EmbeddedView.supported();
    }

    /** The in-game view holds this app's page (shown or hidden), so {@link #showInGame} can show it again as it is. */
    public boolean viewLoaded() {
        return EmbeddedView.ready() && ViewScreen.owner() == this;
    }

    /** Shows the in-game view: loading {@code link}, or (null) the page it already holds ({@link #viewLoaded}). */
    public void showInGame(@Nullable String link) {
        ViewScreen.open(this, link);
    }

    /** Opens a link in the system's browser. */
    public void browse(String link) {
        Util.getPlatform().openUri(link);
    }

    /** The view's Open in browser, or its fallback when the view can't start: what the app chose, else its home page. */
    public void openInBrowser() {
        if (!start()) {
            return;
        }
        if (openInBrowser != null) {
            openInBrowser.accept(this);
        } else {
            browse(link("", false));
        }
    }

    // ---------------------------------------------------------------- pages

    /** Open pages of this app in a browser (the in-game view's page doesn't count). */
    public int browserPages() {
        return sockets.size() - (viewLoaded() && !sockets.isEmpty() ? 1 : 0);
    }

    /** Sends a message to every open page of this app. */
    public void broadcast(String json) {
        if (!sockets.isEmpty()) {
            sockets.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /**
     * Sends a request to the server, as a page's would go: to the app's handler there. Large ones go compressed in
     * pieces. Call it on the game thread, while in a world.
     */
    public void toServer(String json) {
        if (json.length() > RelayPayloads.MAX_REQUEST_CHARS / 3
                && json.getBytes(StandardCharsets.UTF_8).length > RelayPayloads.MAX_REQUEST_CHARS) {
            toServer(json, null);
        } else {
            ClientPacketDistributor.sendToServer(new RelayPayloads.Request(id, json));
        }
    }

    /** Ids for large requests sent in pieces. */
    private static final AtomicInteger NEXT_LARGE = new AtomicInteger();

    void toServer(String json, @Nullable String requestId) {
        for (RelayPayloads.RequestPart part : RelayPayloads.splitRequest(id, NEXT_LARGE.incrementAndGet(), requestId, json)) {
            ClientPacketDistributor.sendToServer(part);
        }
    }

    ChannelGroup sockets() {
        return sockets;
    }

    PageListener listener() {
        return listener;
    }

    /** The app's own answer to a path, if it took one. */
    @Nullable
    Answer route(String path) {
        return routes.get(path);
    }

    String loginHint() {
        return loginHint;
    }

    /**
     * One of the app's files: from its assets, or, when {@code devRootProperty} names a folder (a dev run's build
     * output, say), from there first, so a rebuilt page shows up on a browser refresh.
     */
    @Nullable
    InputStream open(String name) throws IOException {
        String devRoot = devRootProperty == null ? null : System.getProperty(devRootProperty);
        if (devRoot != null) {
            Path root = Path.of(devRoot).toAbsolutePath().normalize();
            Path file = root.resolve(name).normalize();
            if (file.startsWith(root) && Files.isRegularFile(file)) {
                return Files.newInputStream(file);
            }
        }
        return anchor.getResourceAsStream(assets + name);
    }

    /** Answers a GET of one path, off the game thread: the body, or a failed future for a 500. */
    @FunctionalInterface
    public interface Route {
        CompletableFuture<byte[]> get(Map<String, List<String>> query);
    }

    record Answer(String contentType, Route route) {
    }

    public static final class Builder {
        private final String id;
        private final Component title;
        private final Class<?> anchor;
        private final String assets;
        @Nullable
        private String devRootProperty;
        private String loginHint = "Open this page from the game.";
        private final Map<String, Answer> routes = new HashMap<>();
        private PageListener listener = new PageListener() {
        };
        @Nullable
        private Consumer<WebApp> openInBrowser;

        private Builder(String id, Component title, Class<?> anchor, String assets) {
            Telispex.checkAppId(id);
            this.id = id;
            this.title = title;
            this.anchor = anchor;
            this.assets = assets.endsWith("/") ? assets : assets + "/";
        }

        /** A system property naming a folder to serve the files from first (a dev run's build output). */
        public Builder devRoot(String systemProperty) {
            this.devRootProperty = systemProperty;
            return this;
        }

        /** What a browser without a login is told: how to open the page from the game. */
        public Builder loginHint(String text) {
            this.loginHint = text;
            return this;
        }

        /** Answers GETs of {@code path} (from the page's root, "/catalog.json" say) itself, as {@code contentType}. */
        public Builder route(String path, String contentType, Route route) {
            if (!path.startsWith("/") || path.startsWith("/_/") || path.equals("/ws")) {
                throw new IllegalArgumentException("Not a path an app can take: " + path);
            }
            routes.put(path, new Answer(contentType, route));
            return this;
        }

        /** Hears the app's pages: requests it answers on the client, and pages closing. */
        public Builder pages(PageListener listener) {
            this.listener = listener;
            return this;
        }

        /** What the in-game view's Open in browser does (by default, opens the app's page in the browser). */
        public Builder openInBrowser(Consumer<WebApp> action) {
            this.openInBrowser = action;
            return this;
        }

        /** Registers the app. Call it once, from the mod's client setup. */
        public WebApp register() {
            WebApp app = new WebApp(this);
            if (APPS.putIfAbsent(id, app) != null) {
                throw new IllegalStateException("An app named " + id + " is already registered");
            }
            return app;
        }
    }
}
