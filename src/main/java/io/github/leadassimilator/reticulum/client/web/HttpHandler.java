package io.github.leadassimilator.reticulum.client.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import net.minecraft.resources.ResourceLocation;

/**
 * HTTP side of the local web server: the token handshake, static files, item icons and the WebSocket upgrade.
 * Every request must name this server by its loopback address (defeats DNS rebinding) and, except for the token
 * handshake itself, carry the session cookie. The WebSocket upgrade must also come from this origin.
 */
final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String COOKIE = "reticulum_session";
    private static final String WEB_ROOT = "/assets/reticulum/web/";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json",
            "svg", "image/svg+xml",
            "png", "image/png",
            "woff2", "font/woff2");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess() || request.method() != HttpMethod.GET) {
            respond(ctx, request, HttpResponseStatus.BAD_REQUEST, "text/plain", bytes("Bad request"));
            return;
        }
        if (!hostAllowed(request.headers().get(HttpHeaderNames.HOST))) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("Forbidden"));
            return;
        }
        QueryStringDecoder uri = new QueryStringDecoder(request.uri());
        List<String> tokens = uri.parameters().get("token");
        if (tokens != null && !tokens.isEmpty()) {
            handshake(ctx, request, uri, tokens.getFirst());
            return;
        }
        if (!hasSession(request)) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/html; charset=utf-8",
                    bytes("<p>Open the Reticulum editor from the game: the controller's <b>Open editor</b> button.</p>"));
            return;
        }
        String path = uri.path();
        if (path.equals("/ws")) {
            upgrade(ctx, request);
        } else if (path.startsWith("/icon/item/")) {
            icon(ctx, request, path.substring("/icon/item/".length()));
        } else {
            file(ctx, request, path);
        }
    }

    // ---------------------------------------------------------------- security

    private static boolean hostAllowed(@Nullable String host) {
        int port = WebBridge.port();
        return host != null && Set.of("127.0.0.1:" + port, "localhost:" + port).contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean originAllowed(@Nullable String origin) {
        int port = WebBridge.port();
        return origin != null && Set.of("http://127.0.0.1:" + port, "http://localhost:" + port).contains(origin.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), WebBridge.token().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasSession(FullHttpRequest request) {
        String header = request.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return false;
        }
        for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
            if (cookie.name().equals(COOKIE) && tokenMatches(cookie.value())) {
                return true;
            }
        }
        return false;
    }

    /** Trades the token in the URL for a session cookie and redirects to the same page without it. */
    private static void handshake(ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder uri, String candidate) {
        if (!tokenMatches(candidate)) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("This editor link has expired; open it again from the game."));
            return;
        }
        DefaultCookie cookie = new DefaultCookie(COOKIE, WebBridge.token());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, uri.path());
        response.headers().set(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        HttpUtil.setContentLength(response, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    // ---------------------------------------------------------------- routes

    private static void upgrade(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!originAllowed(request.headers().get(HttpHeaderNames.ORIGIN))) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("Forbidden origin"));
            return;
        }
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                "ws://127.0.0.1:" + WebBridge.port() + "/ws", null, false, 64 * 1024);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        ctx.pipeline().replace(ctx.handler(), "ws", new SocketHandler(handshaker));
        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (future.isSuccess()) {
                WebBridge.sockets().add(ctx.channel());
            }
        });
    }

    private static void icon(ChannelHandlerContext ctx, FullHttpRequest request, String rest) {
        String name = rest.endsWith(".png") ? rest.substring(0, rest.length() - 4) : rest;
        int slash = name.indexOf('/');
        ResourceLocation id = slash < 0 ? null : ResourceLocation.tryBuild(name.substring(0, slash), name.substring(slash + 1));
        if (id == null) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
            return;
        }
        FullHttpRequest retained = request.retainedDuplicate();
        IconRenderer.item(id).whenComplete((png, error) -> ctx.executor().execute(() -> {
            try {
                if (error != null || png == null) {
                    respond(ctx, retained, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", bytes("Icon failed"));
                } else {
                    respondCached(ctx, retained, png);
                }
            } finally {
                retained.release();
            }
        }));
    }

    private static void file(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("\\") || name.startsWith("/")) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
            return;
        }
        try (InputStream in = HttpHandler.class.getResourceAsStream(WEB_ROOT + name)) {
            if (in == null) {
                respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
                return;
            }
            String extension = name.substring(name.lastIndexOf('.') + 1);
            respond(ctx, request, HttpResponseStatus.OK, TYPES.getOrDefault(extension, "application/octet-stream"), in.readAllBytes());
        } catch (IOException e) {
            Reticulum.LOGGER.warn("Could not serve {}", name, e);
            respond(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", bytes("Error"));
        }
    }

    // ---------------------------------------------------------------- responses

    private static void respondCached(ChannelHandlerContext ctx, FullHttpRequest request, byte[] png) {
        FullHttpResponse response = response(HttpResponseStatus.OK, "image/png", png);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=3600");
        send(ctx, request, response);
    }

    private static void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String type, byte[] body) {
        FullHttpResponse response = response(status, type, body);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        send(ctx, request, response);
    }

    private static FullHttpResponse response(HttpResponseStatus status, String type, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set("X-Frame-Options", "DENY");
        HttpUtil.setContentLength(response, body.length);
        return response;
    }

    private static void send(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        var future = ctx.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
