package io.github.leadassimilator.reticulum.client.web;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.protocol.web.WebPayloads;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The web UI's local server, hosted by the game client. By default it binds to 127.0.0.1 on port 38291 (or the port
 * it last used, or a free one)
 * (both configurable), and admits a browser only after it has presented the one-time token from the URL the game opened. Requests are
 * relayed to the server as they are; the server checks every one of them against the player's permissions.
 */
public final class WebBridge {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Nullable
    private static EventLoopGroup group;
    @Nullable
    private static Channel server;
    private static int port;
    private static String boundAddress = "";
    private static int boundPort = -1;
    private static Set<String> authorities = Set.of();
    private static String baseUrl = "";
    private static String token = "";
    private static final ChannelGroup SOCKETS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Map<Integer, List<byte[]>> PARTS = new HashMap<>();
    @Nullable
    private static UUID focus;

    private WebBridge() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** The port tried first when none is configured. */
    static final int DEFAULT_PORT = 38291;

    /** Remembered between runs: the session secret, so bookmarks and open pages keep working, and the port last used. */
    private static Path stateFile() {
        return FMLPaths.CONFIGDIR.get().resolve("reticulum-web.json");
    }

    private static JsonObject loadState() {
        try {
            if (Files.exists(stateFile())) {
                return JsonParser.parseString(Files.readString(stateFile())).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            Reticulum.LOGGER.warn("Could not read {}; starting afresh", stateFile(), e);
        }
        return new JsonObject();
    }

    private static void saveState(JsonObject state) {
        try {
            Files.writeString(stateFile(), state.toString());
        } catch (IOException e) {
            Reticulum.LOGGER.warn("Could not save {}", stateFile(), e);
        }
    }

    static synchronized void start() {
        String address = ReticulumClientConfig.WEB_ADDRESS.get().strip();
        int wantedPort = ReticulumClientConfig.WEB_PORT.getAsInt();
        if (server != null) {
            if (address.equals(boundAddress) && wantedPort == boundPort) {
                return;
            }
            // The config changed since the server started: restart it with the new address or port.
            stop();
        }
        JsonObject state = loadState();
        boolean remember = ReticulumClientConfig.REMEMBER_LOGIN.getAsBoolean();
        if (remember && state.has("secret") && state.get("secret").getAsString().matches("[0-9a-f]{64}")) {
            token = state.get("secret").getAsString();
        } else {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            token = HexFormat.of().formatHex(bytes);
            if (remember) {
                state.addProperty("secret", token);
                saveState(state);
            } else if (state.remove("secret") != null) {
                // Not remembering any more: don't leave the old secret lying around.
                saveState(state);
            }
        }
        // Ports to try in order: the configured one, or the remembered one and the default; then any free port.
        List<Integer> ports = new ArrayList<>();
        if (wantedPort > 0) {
            ports.add(wantedPort);
        } else {
            if (state.has("port")) {
                ports.add(state.get("port").getAsInt());
            }
            ports.add(DEFAULT_PORT);
        }
        ports.add(0);
        // Daemon threads, so the game can always exit even if the server is still up.
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("reticulum-web", true));
        try {
            InetAddress bindTo = InetAddress.getByName(address);
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(64 * 1024), new HttpHandler());
                        }
                    });
            Exception lastFailure = null;
            for (int candidate : ports.stream().distinct().toList()) {
                try {
                    server = bootstrap.bind(new InetSocketAddress(bindTo, candidate)).syncUninterruptibly().channel();
                    break;
                } catch (Exception e) {
                    // Netty rethrows the BindException as is, although it is checked, so catch everything.
                    // Most likely taken by something else (another game instance, say); try the next one.
                    Reticulum.LOGGER.info("Reticulum web editor could not use port {} ({}); trying another", candidate, e.getMessage());
                    lastFailure = e;
                }
            }
            if (server == null) {
                throw new IllegalStateException("no port could be used", lastFailure);
            }
            InetSocketAddress bound = (InetSocketAddress) server.localAddress();
            if (wantedPort == 0 && (!state.has("port") || state.get("port").getAsInt() != bound.getPort())) {
                state.addProperty("port", bound.getPort());
                saveState(state);
            }
            port = bound.getPort();
            boundAddress = address;
            boundPort = wantedPort;
            authorities = authorities(address, bound.getAddress(), port);
            baseUrl = baseUrl(address, bound.getAddress(), port);
            if (bound.getAddress().isLoopbackAddress()) {
                Reticulum.LOGGER.info("Reticulum web editor listening on {}", bound);
            } else {
                Reticulum.LOGGER.warn("Reticulum web editor listening on {}, which other machines can reach (webAddress in the client config)", bound);
            }
        } catch (UnknownHostException | RuntimeException e) {
            Reticulum.LOGGER.error("Could not start the Reticulum web editor on {}:{}", address, wantedPort, e);
            stop();
        }
    }

    /** Every host:port a browser may use to reach the server. Requests naming anything else are refused. */
    private static Set<String> authorities(String configured, InetAddress bound, int port) {
        Set<String> out = new HashSet<>();
        if (bound.isLoopbackAddress() || bound.isAnyLocalAddress()) {
            out.add("127.0.0.1:" + port);
            out.add("localhost:" + port);
            out.add("[::1]:" + port);
        }
        if (bound.isAnyLocalAddress()) {
            try {
                for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                        out.add(hostPart(a) + ":" + port);
                    }
                }
            } catch (SocketException e) {
                Reticulum.LOGGER.warn("Could not list network interfaces for the web editor", e);
            }
        } else {
            out.add(hostPart(bound) + ":" + port);
        }
        if (isHostname(configured)) {
            out.add(configured.toLowerCase(Locale.ROOT) + ":" + port);
        }
        return Set.copyOf(out);
    }

    private static String baseUrl(String configured, InetAddress bound, int port) {
        if (bound.isAnyLocalAddress() || bound instanceof Inet4Address && bound.isLoopbackAddress()) {
            return "http://127.0.0.1:" + port;
        }
        return "http://" + (isHostname(configured) ? configured : hostPart(bound)) + ":" + port;
    }

    private static boolean isHostname(String configured) {
        return !configured.isEmpty() && !Character.isDigit(configured.charAt(0)) && !configured.contains(":");
    }

    /** An address as it appears in a URL: IPv6 in brackets, without a zone id. */
    private static String hostPart(InetAddress address) {
        String text = address.getHostAddress();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        return (address instanceof Inet6Address ? "[" + text + "]" : text).toLowerCase(Locale.ROOT);
    }

    static synchronized void stop() {
        SOCKETS.close();
        if (server != null) {
            server.close();
            server = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }

    static int port() {
        return port;
    }

    /** host:port values (lowercase) a request's Host header may name. */
    static Set<String> authorities() {
        return authorities;
    }

    /** e.g. http://127.0.0.1:12345, with no trailing slash. */
    static String baseUrl() {
        return baseUrl;
    }

    static String token() {
        return token;
    }

    static ChannelGroup sockets() {
        return SOCKETS;
    }

    @Nullable
    static UUID focus() {
        return focus;
    }

    // ---------------------------------------------------------------- opening the editor

    /**
     * Shows the editor on a network: inside the game on Windows (when enabled and WebView2 works), else in the
     * browser. If a page is already connected it just switches to the network there.
     */
    public static void openEditor(@Nullable UUID network) {
        start();
        if (server == null) {
            return;
        }
        focus = network;
        if (io.github.leadassimilator.reticulum.ReticulumClientConfig.EDITOR_IN_GAME.getAsBoolean()
                && io.github.leadassimilator.reticulum.client.webview.WebView2Host.supported()) {
            if (io.github.leadassimilator.reticulum.client.webview.WebView2Host.ready()) {
                // The page is loaded (hidden); show it again, on this network.
                broadcastFocus(network);
                io.github.leadassimilator.reticulum.client.webview.EditorScreen.open(null);
            } else {
                io.github.leadassimilator.reticulum.client.webview.EditorScreen.open(editorUrl(network));
            }
            return;
        }
        openInBrowser();
    }

    /** The one-time login link for the editor, opening on a network. */
    private static String editorUrl(@Nullable UUID network) {
        return baseUrl + "/?token=" + token + (network == null ? "" : "#network=" + network);
    }

    private static void broadcastFocus(@Nullable UUID network) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "focus");
        if (network != null) {
            message.addProperty("network", network.toString());
        }
        broadcast(message.toString());
    }

    /**
     * Shows the editor in the browser, on the network last asked for. If a browser page is already connected it just
     * switches tabs there; otherwise the browser is opened. The link is also put in chat for players who prefer to
     * open it themselves.
     */
    public static void openInBrowser() {
        start();
        if (server == null) {
            return;
        }
        UUID network = focus;
        // A connected page (unless it's the in-game one, which can't tell us apart from a browser tab) just switches.
        if (!SOCKETS.isEmpty() && !io.github.leadassimilator.reticulum.client.webview.WebView2Host.ready()) {
            broadcastFocus(network);
            chat(Component.translatable("message.reticulum.editor_focused"));
            return;
        }
        String url = editorUrl(network);
        Util.getPlatform().openUri(url);
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

    // ---------------------------------------------------------------- relaying

    static void broadcast(String json) {
        if (!SOCKETS.isEmpty()) {
            SOCKETS.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /** A piece of a server message; once all pieces are in, the message goes to every open page. */
    public static void onChunk(WebPayloads.Chunk chunk) {
        List<byte[]> parts = PARTS.computeIfAbsent(chunk.message(), k -> new ArrayList<>());
        parts.add(chunk.data());
        if (parts.size() < chunk.total()) {
            return;
        }
        PARTS.remove(chunk.message());
        try {
            broadcast(WebPayloads.join(parts));
        } catch (java.util.zip.DataFormatException e) {
            Reticulum.LOGGER.warn("Dropped a corrupt web UI message from the server", e);
        }
    }

    /**
     * Minecraft pauses by opening the pause screen when its window loses focus; in singleplayer that stops the
     * server. While an editor page is connected, that automatic pause is skipped (Esc still pauses).
     */
    public static void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen && !mc.isWindowActive()
                && !SOCKETS.isEmpty() && mc.screen == null && ReticulumClientConfig.KEEP_RUNNING_WHILE_EDITING.getAsBoolean()) {
            event.setCanceled(true);
        }
    }

    /**
     * Joining a world starts the editor's server right away (not on the first Open editor), so bookmarked or open
     * pages connect as soon as there is something to edit. It keeps running after leaving, for the next world.
     */
    public static void onLoggedIn() {
        start();
        broadcast("{\"type\":\"connected\"}");
    }

    public static void onLoggedOut() {
        PARTS.clear();
        broadcast("{\"type\":\"disconnected\"}");
    }
}
