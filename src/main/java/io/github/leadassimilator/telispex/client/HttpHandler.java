package io.github.leadassimilator.telispex.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import io.github.leadassimilator.telispex.Telispex;
import io.github.leadassimilator.telispex.TelispexConfig;
import io.github.leadassimilator.telispex.relay.RelayPayloads;
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
 * HTTP side of the web server: the token handshake, each app's files and routes, icons and the WebSocket upgrade. Every
 * request must name this server by one of its own addresses or an app's own host (defeats DNS rebinding) and, except
 * for the token handshake itself, carry the session cookie of the app it is for. The WebSocket upgrade must also come
 * from the page's own origin.
 */
final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    /** An app's login cookie's name, per port (cookies aren't kept apart by port, so two games would share one). */
    private static String cookieName(WebApp app) {
        return "telispex_" + app.id() + "_" + WebServer.port();
    }

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
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (!WebServer.hostAllowed(host)) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("Forbidden"));
            return;
        }
        QueryStringDecoder uri = new QueryStringDecoder(request.uri());
        if (WebServer.needsSlash(host, uri.path())) {
            redirect(ctx, request, uri.path() + "/" + (uri.rawQuery().isEmpty() ? "" : "?" + uri.rawQuery()));
            return;
        }
        WebServer.Target target = WebServer.target(host, uri.path());
        if (target == null) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
            return;
        }
        WebApp app = target.app();
        List<String> tokens = uri.parameters().get("token");
        if (tokens != null && !tokens.isEmpty()) {
            handshake(ctx, request, uri, target, tokens.getFirst());
            return;
        }
        if (!hasSession(request, app)) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/html; charset=utf-8", bytes("<p>" + escape(app.loginHint()) + "</p>"));
            return;
        }
        String path = target.path();
        WebApp.Answer answer = app.route(path);
        if (path.equals("/ws")) {
            upgrade(ctx, request, target);
        } else if (path.startsWith("/_/icon/")) {
            icon(ctx, request, path.substring("/_/icon/".length()));
        } else if (answer != null) {
            respondLater(ctx, request, answer.route().get(uri.parameters()), "Failed",
                    (retained, body) -> respond(ctx, retained, HttpResponseStatus.OK, answer.contentType(), body));
        } else {
            file(ctx, request, app, path);
        }
    }

    // ---------------------------------------------------------------- security

    private static boolean tokenMatches(String candidate, WebApp app) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), WebServer.token(app).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasSession(FullHttpRequest request, WebApp app) {
        String header = request.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return false;
        }
        for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
            if (cookie.name().equals(cookieName(app)) && tokenMatches(cookie.value(), app)) {
                return true;
            }
        }
        return false;
    }

    /** Trades the token in the URL for a session cookie and redirects to the same page without it. */
    private static void handshake(ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder uri, WebServer.Target target,
            String candidate) {
        if (!tokenMatches(candidate, target.app())) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("This link has expired — open it again from the game."));
            return;
        }
        DefaultCookie cookie = new DefaultCookie(cookieName(target.app()), WebServer.token(target.app()));
        cookie.setHttpOnly(true);
        // Only the app's own paths where apps share a host; on its own host, all of it.
        cookie.setPath(target.prefix() + "/");
        // Kept across browser and game restarts (the secret is kept too), so bookmarks and open pages keep working;
        // unless the player chose a login per session, which ends when the browser closes.
        if (TelispexConfig.REMEMBER_LOGIN.getAsBoolean()) {
            cookie.setMaxAge(90L * 24 * 60 * 60);
        }
        cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, uri.path());
        response.headers().set(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        HttpUtil.setContentLength(response, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /** An app named by path without the slash after it: on to the page's root, so its relative links resolve. */
    private static void redirect(ChannelHandlerContext ctx, FullHttpRequest request, String location) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.MOVED_PERMANENTLY);
        response.headers().set(HttpHeaderNames.LOCATION, location);
        HttpUtil.setContentLength(response, 0);
        send(ctx, request, response);
    }

    // ---------------------------------------------------------------- routes

    private static void upgrade(ChannelHandlerContext ctx, FullHttpRequest request, WebServer.Target target) {
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (!WebServer.sameOrigin(request.headers().get(HttpHeaderNames.ORIGIN), host)) {
            respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", bytes("Forbidden origin"));
            return;
        }
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                // Room past the largest request, so one a little over it gets an answer rather than a dropped page.
                "ws://" + host + target.prefix() + "/ws", null, false, 2 * RelayPayloads.MAX_REQUEST_BYTES);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        ctx.pipeline().replace(ctx.handler(), "ws", new SocketHandler(handshaker, target.app()));
        // Browsers send a large message as several frames: joined into one before it is read.
        ctx.pipeline().addBefore("ws", "wsjoin", new io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator(2 * RelayPayloads.MAX_REQUEST_BYTES));
        handshaker.handshake(ctx.channel(), request).addListener(future -> {
            if (future.isSuccess()) {
                target.app().sockets().add(ctx.channel());
            }
        });
    }

    /** {@code <kind>/<namespace>/<path>.png}: an icon of a kind {@link Icons} draws. */
    private static void icon(ChannelHandlerContext ctx, FullHttpRequest request, String rest) {
        String name = rest.endsWith(".png") ? rest.substring(0, rest.length() - 4) : rest;
        int kindEnd = name.indexOf('/');
        int slash = kindEnd < 0 ? -1 : name.indexOf('/', kindEnd + 1);
        ResourceLocation id = slash < 0 ? null : ResourceLocation.tryBuild(name.substring(kindEnd + 1, slash), name.substring(slash + 1));
        java.util.concurrent.CompletableFuture<byte[]> png = id == null ? null : Icons.get(name.substring(0, kindEnd), id);
        if (png == null) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
            return;
        }
        respondLater(ctx, request, png, "Icon failed", (retained, body) -> respondCached(ctx, retained, body));
    }

    /**
     * Answers once {@code result} is ready (on the game thread, say), back on the connection's own thread; the request
     * is kept alive until then.
     */
    private static void respondLater(ChannelHandlerContext ctx, FullHttpRequest request, java.util.concurrent.CompletableFuture<byte[]> result,
            String failure, java.util.function.BiConsumer<FullHttpRequest, byte[]> answer) {
        FullHttpRequest retained = request.retainedDuplicate();
        result.whenComplete((body, error) -> ctx.executor().execute(() -> {
            try {
                if (error != null || body == null) {
                    respond(ctx, retained, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", bytes(failure));
                } else {
                    answer.accept(retained, body);
                }
            } finally {
                retained.release();
            }
        }));
    }

    private static void file(ChannelHandlerContext ctx, FullHttpRequest request, WebApp app, String path) {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("\\") || name.startsWith("/")) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
            return;
        }
        try (InputStream in = app.open(name)) {
            if (in == null) {
                respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", bytes("Not found"));
                return;
            }
            String extension = name.substring(name.lastIndexOf('.') + 1);
            respond(ctx, request, HttpResponseStatus.OK, TYPES.getOrDefault(extension, "application/octet-stream"), in.readAllBytes());
        } catch (IOException e) {
            Telispex.LOGGER.warn("Could not serve {} for {}", name, app.id(), e);
            respond(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", bytes("Error"));
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- responses

    /**
     * An icon, checked again each time it is shown (by its tag: a 304 when unchanged), so one drawn anew after a resource
     * reload replaces the old one in the page.
     */
    private static void respondCached(ChannelHandlerContext ctx, FullHttpRequest request, byte[] png) {
        String tag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(png)) + "\"";
        if (tag.equals(request.headers().get(HttpHeaderNames.IF_NONE_MATCH))) {
            FullHttpResponse notModified = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
            notModified.headers().set(HttpHeaderNames.ETAG, tag);
            HttpUtil.setContentLength(notModified, 0);
            send(ctx, request, notModified);
            return;
        }
        FullHttpResponse response = response(HttpResponseStatus.OK, "image/png", png);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, no-cache");
        response.headers().set(HttpHeaderNames.ETAG, tag);
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
