package io.github.leadassimilator.telispex.client;

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
import java.util.zip.DataFormatException;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.telispex.Telispex;
import io.github.leadassimilator.telispex.TelispexConfig;
import io.github.leadassimilator.telispex.client.view.EmbeddedView;
import io.github.leadassimilator.telispex.relay.RelayPayloads;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The local web server every app's pages come from, hosted by the game client. By default it binds to 127.0.0.1 on
 * port 38291 (or the port it last used, or a free one), both configurable. Each app's page is at its own host,
 * {@code <app>.localhost}, where the browser can reach one (so pages of different apps are different origins and
 * can't reach each other), else at {@code /<app>/} on the one host. A browser is admitted to an app only after it has
 * presented that app's one-time token from the link the game opened.
 */
public final class WebServer {
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
    /** Whether the server takes this computer's 127.0.0.1, where *.localhost names lead. */
    private static boolean loopback;
    /** Each app's login secret, by app id. */
    private static final Map<String, String> TOKENS = new HashMap<>();

    private WebServer() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** The port tried first when none is configured. */
    static final int DEFAULT_PORT = 38291;

    /** Remembered between runs: each app's secret, so bookmarks and open pages keep working, and the port last used. */
    private static Path stateFile() {
        return FMLPaths.CONFIGDIR.get().resolve("telispex-web.json");
    }

    private static JsonObject loadState() {
        try {
            if (Files.exists(stateFile())) {
                return JsonParser.parseString(Files.readString(stateFile())).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            Telispex.LOGGER.warn("Could not read {}; starting afresh", stateFile(), e);
        }
        return new JsonObject();
    }

    private static void saveState(JsonObject state) {
        try {
            Files.writeString(stateFile(), state.toString());
        } catch (IOException e) {
            Telispex.LOGGER.warn("Could not save {}", stateFile(), e);
        }
    }

    /** Starts the server, or restarts it when its address or port changed in the config. False when it isn't running. */
    static synchronized boolean start() {
        String address = TelispexConfig.ADDRESS.get().strip();
        int wantedPort = TelispexConfig.PORT.getAsInt();
        if (server != null) {
            if (address.equals(boundAddress) && wantedPort == boundPort) {
                return true;
            }
            // The config changed since the server started: restart it with the new address or port.
            stop();
        }
        JsonObject state = loadState();
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
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("telispex-web", true));
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
                    Telispex.LOGGER.info("Web pages could not use port {} ({}); trying another", candidate, e.getMessage());
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
            loopback = bound.getAddress().isAnyLocalAddress()
                    || bound.getAddress() instanceof Inet4Address && bound.getAddress().getHostAddress().equals("127.0.0.1");
            if (bound.getAddress().isLoopbackAddress()) {
                Telispex.LOGGER.info("Web pages served on {}", bound);
            } else {
                Telispex.LOGGER.warn("Web pages served on {}, which other machines can reach (address in telispex-client.toml)", bound);
            }
            return true;
        } catch (UnknownHostException | RuntimeException e) {
            Telispex.LOGGER.error("Could not serve web pages on {}:{}", address, wantedPort, e);
            stop();
            return false;
        }
    }

    /** Every host:port a browser may use to reach the server by its address. Requests naming anything else are refused. */
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
                Telispex.LOGGER.warn("Could not list network interfaces for the web pages", e);
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
        for (WebApp app : WebApp.all()) {
            app.sockets().close();
        }
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

    // ---------------------------------------------------------------- where each app is

    /**
     * Whether pages get their own host ({@code <app>.localhost}) rather than a path. Only where the server takes
     * 127.0.0.1; AUTO also leaves out browsers and views not known to take such names (Safari, WKWebView, WebKitGTK).
     */
    static boolean subdomains(boolean inGame) {
        if (!loopback) {
            return false;
        }
        return switch (TelispexConfig.HOSTS.get()) {
            case PATH -> false;
            case SUBDOMAIN -> true;
            case AUTO -> inGame ? EmbeddedView.takesLocalhostNames() : Util.getPlatform() != Util.OS.OSX;
        };
    }

    /** Where an app's page is, with no trailing slash: http://mymod.localhost:38291, or http://127.0.0.1:38291/mymod. */
    static String origin(WebApp app, boolean inGame) {
        return subdomains(inGame) ? "http://" + Telispex.hostLabel(app.id()) + ".localhost:" + port : baseUrl + "/" + app.id();
    }

    /** A request's app, and its path within the app's page. */
    record Target(WebApp app, String path, String prefix) {
    }

    /** What a request's Host header must name: one of the server's own addresses, or an app's own host. */
    static boolean hostAllowed(@Nullable String host) {
        return host != null && (authorities.contains(host.toLowerCase(Locale.ROOT)) || appByHost(host) != null);
    }

    @Nullable
    private static WebApp appByHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        String suffix = ".localhost:" + port;
        if (!loopback || !lower.endsWith(suffix)) {
            return null;
        }
        String label = lower.substring(0, lower.length() - suffix.length());
        return label.contains("_") ? null : WebApp.byId(label.replace('-', '_'));
    }

    /**
     * The app a request is for: by its host, else by the first part of its path. Null when it names none (or names
     * one by path, but without the slash after it: {@link #needsSlash} tells those apart).
     */
    @Nullable
    static Target target(String host, String path) {
        WebApp byHost = appByHost(host);
        if (byHost != null) {
            return new Target(byHost, path, "");
        }
        int slash = path.indexOf('/', 1);
        if (slash < 0) {
            return null;
        }
        WebApp app = WebApp.byId(path.substring(1, slash));
        return app == null ? null : new Target(app, path.substring(slash), path.substring(0, slash));
    }

    /** A path that names an app on the one host but leaves out the slash after it (/mymod): sent on to /mymod/. */
    static boolean needsSlash(String host, String path) {
        return appByHost(host) == null && path.length() > 1 && path.indexOf('/', 1) < 0 && WebApp.byId(path.substring(1)) != null;
    }

    /** Whether an origin (a page asking for a WebSocket) is the request's own: the scheme and the host it names. */
    static boolean sameOrigin(@Nullable String origin, @Nullable String host) {
        return origin != null && host != null && origin.toLowerCase(Locale.ROOT).equals("http://" + host.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- logins

    /**
     * An app's login secret: kept across runs (with the port) unless the player chose a login per session, which gets a
     * new one each time the game starts.
     */
    static synchronized String token(WebApp app) {
        String token = TOKENS.get(app.id());
        if (token != null) {
            return token;
        }
        JsonObject state = loadState();
        JsonObject secrets = state.has("secrets") && state.get("secrets").isJsonObject() ? state.getAsJsonObject("secrets") : new JsonObject();
        boolean remember = TelispexConfig.REMEMBER_LOGIN.getAsBoolean();
        if (remember && secrets.has(app.id()) && secrets.get(app.id()).getAsString().matches("[0-9a-f]{64}")) {
            token = secrets.get(app.id()).getAsString();
        } else {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            token = HexFormat.of().formatHex(bytes);
            if (remember) {
                secrets.addProperty(app.id(), token);
                state.add("secrets", secrets);
                saveState(state);
            } else if (secrets.remove(app.id()) != null) {
                // Not remembering any more: don't leave the old secret lying around.
                state.add("secrets", secrets);
                saveState(state);
            }
        }
        TOKENS.put(app.id(), token);
        return token;
    }

    // ---------------------------------------------------------------- from the server

    /** Pieces of server messages being received, by message id (one count across apps). */
    private static final Map<Integer, List<byte[]>> PARTS = new HashMap<>();

    /** A piece of a server message; once all pieces are in, the message goes to every open page of its app. */
    public static void onChunk(RelayPayloads.Chunk chunk) {
        if (chunk.total() <= 0 || chunk.total() > RelayPayloads.MAX_CHUNKS) {
            return;
        }
        if (!PARTS.containsKey(chunk.message()) && PARTS.size() >= 64) {
            // Messages that never finished (a server that stopped mid-message) don't pile up.
            PARTS.clear();
        }
        List<byte[]> parts = PARTS.computeIfAbsent(chunk.message(), k -> new ArrayList<>());
        parts.add(chunk.data());
        if (parts.size() < chunk.total()) {
            return;
        }
        PARTS.remove(chunk.message());
        WebApp app = WebApp.byId(chunk.app());
        if (app == null) {
            return;
        }
        try {
            app.broadcast(RelayPayloads.join(parts));
        } catch (DataFormatException e) {
            Telispex.LOGGER.warn("Dropped a corrupt message for {}'s pages from the server", app.id(), e);
        }
    }

    // ---------------------------------------------------------------- the game

    /** Pages open in a browser, of every app (the in-game view's page doesn't count). */
    static int browserPages() {
        int pages = 0;
        for (WebApp app : WebApp.all()) {
            pages += app.browserPages();
        }
        return pages;
    }

    /**
     * Minecraft pauses by opening the pause screen when its window loses focus; in singleplayer that stops the
     * server. While a page is open in a browser, that automatic pause is skipped (Esc still pauses).
     */
    static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getNewScreen() instanceof PauseScreen && !mc.isWindowActive()
                && mc.screen == null && TelispexConfig.KEEP_RUNNING.getAsBoolean() && browserPages() > 0) {
            event.setCanceled(true);
        }
    }

    /**
     * Joining a world starts the server right away (not when a page is first opened), so bookmarked or open pages
     * connect as soon as there is something to show. It keeps running after leaving, for the next world.
     */
    static void onLoggedIn() {
        if (WebApp.all().isEmpty()) {
            return;
        }
        start();
        for (WebApp app : WebApp.all()) {
            app.broadcast("{\"type\":\"connected\"}");
        }
    }

    static void onLoggedOut() {
        PARTS.clear();
        for (WebApp app : WebApp.all()) {
            app.broadcast("{\"type\":\"disconnected\"}");
        }
    }
}
