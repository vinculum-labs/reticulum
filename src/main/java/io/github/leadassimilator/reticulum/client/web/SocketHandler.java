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
        String id = request.has("id") ? request.get("id").getAsString() : null;
        if (request.has("op") && "hello".equals(request.get("op").getAsString())) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("connected", Minecraft.getInstance().getConnection() != null);
            if (WebBridge.focus() != null) {
                hello.addProperty("focus", WebBridge.focus().toString());
            }
            ctx.writeAndFlush(new TextWebSocketFrame(hello.toString()));
            return;
        }
        String op = request.has("op") ? request.get("op").getAsString() : "";
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
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                reply(ctx, id, "Not in a world");
                return;
            }
            PacketDistributor.sendToServer(new WebPayloads.Request(json));
        });
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
