package io.github.leadassimilator.reticulum.client.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import io.github.leadassimilator.reticulum.Reticulum;
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

/**
 * The web UI's local server, hosted by the game client. It binds to 127.0.0.1 only, on a random free port, and
 * admits a browser only after it has presented the one-time token from the URL the game opened. Requests are
 * relayed to the server as they are; the server checks every one of them against the player's permissions.
 */
public final class WebBridge {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Nullable
    private static EventLoopGroup group;
    @Nullable
    private static Channel server;
    private static int port;
    private static String token = "";
    private static final ChannelGroup SOCKETS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Map<Integer, List<byte[]>> PARTS = new HashMap<>();
    @Nullable
    private static UUID focus;

    private WebBridge() {
    }

    // ---------------------------------------------------------------- lifecycle

    static synchronized void start() {
        if (server != null) {
            return;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        // Daemon threads, so the game can always exit even if the server is still up.
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("reticulum-web", true));
        try {
            server = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(64 * 1024), new HttpHandler());
                        }
                    })
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .syncUninterruptibly()
                    .channel();
            port = ((InetSocketAddress) server.localAddress()).getPort();
            Reticulum.LOGGER.info("Reticulum web editor listening on 127.0.0.1:{}", port);
        } catch (RuntimeException e) {
            Reticulum.LOGGER.error("Could not start the Reticulum web editor", e);
            stop();
        }
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
     * Shows the editor on a network. If a browser page is already connected it just switches tabs there; otherwise
     * the browser is opened. The link is also put in chat for players who prefer to open it themselves.
     */
    public static void openEditor(@Nullable UUID network) {
        start();
        if (server == null) {
            return;
        }
        focus = network;
        if (!SOCKETS.isEmpty()) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "focus");
            if (network != null) {
                message.addProperty("network", network.toString());
            }
            broadcast(message.toString());
            chat(Component.translatable("message.reticulum.editor_focused"));
            return;
        }
        String url = "http://127.0.0.1:" + port + "/?token=" + token + (network == null ? "" : "#network=" + network);
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

    public static void onLoggedIn() {
        broadcast("{\"type\":\"connected\"}");
    }

    public static void onLoggedOut() {
        PARTS.clear();
        broadcast("{\"type\":\"disconnected\"}");
    }
}
