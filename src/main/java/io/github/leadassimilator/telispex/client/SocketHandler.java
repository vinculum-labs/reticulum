package io.github.leadassimilator.telispex.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.telispex.client.view.ViewScreen;
import io.github.leadassimilator.telispex.relay.RelayPayloads;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import net.minecraft.client.Minecraft;

/**
 * One open page of an app. Text frames are requests: the view's own ({@code closeView}, {@code toggleFullscreen}) are
 * answered here, then the app's listener takes what it answers on the client, and everything else goes to the server
 * unchanged, on the game thread.
 */
final class SocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final WebSocketServerHandshaker handshaker;
    private final WebApp app;
    private Page page;

    SocketHandler(WebSocketServerHandshaker handshaker, WebApp app) {
        this.handshaker = handshaker;
        this.app = app;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        page = new Page(app, ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame close) {
            handshaker.close(ctx.channel(), close.retain());
        } else if (frame instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (frame instanceof TextWebSocketFrame text) {
            onText(text.text());
        }
    }

    private void onText(String json) {
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            page.refuse(null, "Malformed request");
            return;
        }
        String id = request.has("id") && request.get("id").isJsonPrimitive() ? request.get("id").getAsString() : null;
        String op = request.has("op") && request.get("op").isJsonPrimitive() ? request.get("op").getAsString() : "";
        if (op.equals("closeView") || op.equals("toggleFullscreen")) {
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (!(mc.screen instanceof ViewScreen) || ViewScreen.owner() != app) {
                    return;
                }
                if (op.equals("closeView")) {
                    // Esc at the root of the page in the game: hide it (the page stays loaded for next time).
                    mc.setScreen(null);
                } else {
                    // F11 in the in-game view, done as the game does it.
                    mc.getWindow().toggleFullScreen();
                    mc.options.fullscreen().set(mc.getWindow().isFullscreen());
                }
            });
            page.reply(id);
            return;
        }
        if (app.listener().onRequest(page, op, request)) {
            return;
        }
        // Too large for one packet: compressed and sent in pieces.
        int bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        boolean large = bytes > RelayPayloads.MAX_REQUEST_CHARS;
        if (bytes > RelayPayloads.MAX_REQUEST_BYTES) {
            page.refuse(id, "Request too large");
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                page.refuse(id, "Not in a world");
                return;
            }
            if (large) {
                app.toServer(json, id);
            } else {
                app.toServer(json);
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        app.listener().onClose(page);
        super.channelInactive(ctx);
    }
}
