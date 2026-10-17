package io.github.leadassimilator.telispex.client;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import io.github.leadassimilator.telispex.relay.Relay;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/** One open page of an app, connected by its WebSocket. */
public final class Page {
    private final WebApp app;
    private final Channel channel;

    Page(WebApp app, Channel channel) {
        this.app = app;
        this.channel = channel;
    }

    public WebApp app() {
        return app;
    }

    /** Sends this page a message. */
    public void send(String json) {
        channel.writeAndFlush(new TextWebSocketFrame(json));
    }

    public void send(JsonObject message) {
        send(message.toString());
    }

    /** Answers a request: done. */
    public void reply(@Nullable String id) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "response");
        if (id != null) {
            out.addProperty("id", id);
        }
        out.addProperty("ok", true);
        send(out);
    }

    /** Answers a request: refused, with why. */
    public void refuse(@Nullable String id, String error) {
        send(Relay.error(id, error));
    }
}
