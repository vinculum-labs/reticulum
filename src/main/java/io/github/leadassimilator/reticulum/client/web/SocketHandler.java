package io.github.leadassimilator.reticulum.client.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.reticulum.protocol.web.WebPayloads;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One browser page. Text frames are protocol requests: {@code hello} is answered here, everything else is passed
 * to the server unchanged on the game thread.
 */
final class SocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    /**
     * The networks each open page shows. The server keeps one subscription per player, not per page: a page letting
     * go of a network another page still shows mustn't unsubscribe it for both, so that is only sent for the last.
     */
    private static final java.util.Map<io.netty.channel.Channel, java.util.Set<String>> PAGES = new java.util.HashMap<>();

    private final WebSocketServerHandshaker handshaker;

    SocketHandler(WebSocketServerHandshaker handshaker) {
        this.handshaker = handshaker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame close) {
            handshaker.close(ctx.channel(), close.retain());
        } else if (frame instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (frame instanceof TextWebSocketFrame text) {
            onText(ctx, text.text());
        }
    }

    private void onText(ChannelHandlerContext ctx, String json) {
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            reply(ctx, null, "Malformed request");
            return;
        }
        String id = request.has("id") && request.get("id").isJsonPrimitive() ? request.get("id").getAsString() : null;
        String op = request.has("op") && request.get("op").isJsonPrimitive() ? request.get("op").getAsString() : "";
        if ("hello".equals(op)) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("connected", Minecraft.getInstance().getConnection() != null);
            if (WebBridge.focus() != null) {
                hello.addProperty("focus", WebBridge.focus().toString());
            }
            ctx.writeAndFlush(new TextWebSocketFrame(hello.toString()));
            return;
        }
        if (op.equals("closeEditor") || op.equals("toggleFullscreen")) {
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (!(mc.screen instanceof io.github.leadassimilator.reticulum.client.webview.EditorScreen)) {
                    return;
                }
                if (op.equals("closeEditor")) {
                    // Esc at the root of the in-game editor: hide it (the page stays loaded for next time).
                    mc.setScreen(null);
                } else {
                    // F11 in the in-game editor, done as the game does it.
                    mc.getWindow().toggleFullScreen();
                    mc.options.fullscreen().set(mc.getWindow().isFullscreen());
                }
            });
            JsonObject done = new JsonObject();
            done.addProperty("type", "response");
            if (id != null) {
                done.addProperty("id", id);
            }
            done.addProperty("ok", true);
            ctx.writeAndFlush(new TextWebSocketFrame(done.toString()));
            return;
        }
        if (json.length() > WebPayloads.MAX_REQUEST_CHARS) {
            reply(ctx, id, "Request too large");
            return;
        }
        String network = request.has("network") && request.get("network").isJsonPrimitive() ? request.get("network").getAsString() : null;
        if (op.equals("subscribe") && network != null) {
            synchronized (PAGES) {
                PAGES.computeIfAbsent(ctx.channel(), k -> new java.util.HashSet<>()).add(network);
            }
        } else if (op.equals("unsubscribe") && network != null && !letGo(ctx.channel(), network)) {
            // Still shown on another page: only this page lets go.
            JsonObject done = new JsonObject();
            done.addProperty("type", "response");
            if (id != null) {
                done.addProperty("id", id);
            }
            done.addProperty("ok", true);
            ctx.writeAndFlush(new TextWebSocketFrame(done.toString()));
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                reply(ctx, id, "Not in a world");
                return;
            }
            PacketDistributor.sendToServer(new WebPayloads.Request(json));
        });
    }

    /** A page closed: the networks no other page shows are unsubscribed. */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        java.util.Set<String> shown;
        synchronized (PAGES) {
            shown = PAGES.containsKey(ctx.channel()) ? java.util.Set.copyOf(PAGES.get(ctx.channel())) : java.util.Set.of();
        }
        for (String network : shown) {
            if (letGo(ctx.channel(), network)) {
                JsonObject request = new JsonObject();
                request.addProperty("op", "unsubscribe");
                request.addProperty("network", network);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().getConnection() != null) {
                        PacketDistributor.sendToServer(new WebPayloads.Request(request.toString()));
                    }
                });
            }
        }
        synchronized (PAGES) {
            PAGES.remove(ctx.channel());
        }
        super.channelInactive(ctx);
    }

    /** This page stops showing a network; true when no other page shows it (so the server can stop sending it). */
    private static boolean letGo(io.netty.channel.Channel page, String network) {
        synchronized (PAGES) {
            java.util.Set<String> mine = PAGES.get(page);
            if (mine != null) {
                mine.remove(network);
            }
            return PAGES.entrySet().stream().noneMatch(e -> e.getKey() != page && e.getValue().contains(network));
        }
    }

    private static void reply(ChannelHandlerContext ctx, String id, String error) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "response");
        if (id != null) {
            out.addProperty("id", id);
        }
        out.addProperty("ok", false);
        out.addProperty("error", error);
        ctx.writeAndFlush(new TextWebSocketFrame(out.toString()));
    }
}
