package io.github.leadassimilator.telispex.relay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server side of the relay: each app's page requests, passed on unchanged by the player's game client, go to the handler
 * the app registered; its messages go back to the player's pages. Requests and replies share one envelope: a request is
 * {@code {"id": "...", "op": "...", ...}}, a reply {@code {"type": "response", "id": "...", "ok": true|false, "error": "..."}}.
 * The handler checks every request against the player's permissions; the relay only bounds their size and rate.
 */
public final class Relay {
    /** Takes an app's requests on the server thread. */
    @FunctionalInterface
    public interface Handler {
        void onRequest(ServerPlayer player, String json);
    }

    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static int nextMessage;

    private Relay() {
    }

    /** Version of the relay's packets: a client and server with different ones can't join. */
    private static final String VERSION = "1";

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(Relay::registerPayloads);
        NeoForge.EVENT_BUS.addListener(Relay::onLogout);
        NeoForge.EVENT_BUS.addListener(Relay::onServerStopped);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(RelayPayloads.Chunk.TYPE, RelayPayloads.Chunk.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.telispex.client.WebServer.onChunk(payload));
        registrar.playToServer(RelayPayloads.RequestPart.TYPE, RelayPayloads.RequestPart.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                onRequestPart(player, payload);
            }
        });
        registrar.playToServer(RelayPayloads.Request.TYPE, RelayPayloads.Request.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                onRequest(player, payload.app(), payload.json());
            }
        });
    }

    /** Takes the requests of an app's pages, from any player. */
    public static void register(String app, Handler handler) {
        Telispex.checkAppId(app);
        if (HANDLERS.putIfAbsent(app, handler) != null) {
            throw new IllegalStateException("An app named " + app + " already takes requests");
        }
    }

    /**
     * Sends a message to the player's pages of an app. Past what a page takes it would be dropped there without a word:
     * an answer (one with an id) says so instead, and anything else is left out. False when it wasn't sent as given.
     */
    public static boolean send(ServerPlayer player, String app, JsonObject message) {
        String text = message.toString();
        boolean whole = true;
        if (text.length() > RelayPayloads.MAX_MESSAGE_BYTES / 3
                && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RelayPayloads.MAX_MESSAGE_BYTES) {
            if (!message.has("id")) {
                Telispex.LOGGER.warn("A {} for {}'s pages was too large to send ({} characters)",
                        message.has("type") ? message.get("type").getAsString() : "message", app, text.length());
                return false;
            }
            text = error(message.get("id").getAsString(), "That is too large to show").toString();
            whole = false;
        }
        for (RelayPayloads.Chunk chunk : RelayPayloads.split(app, nextMessage++, text)) {
            PacketDistributor.sendToPlayer(player, chunk);
        }
        return whole;
    }

    /** A refusal in the reply envelope. */
    public static JsonObject error(@Nullable String id, String error) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "response");
        if (id != null) {
            out.addProperty("id", id);
        }
        out.addProperty("ok", false);
        out.addProperty("error", error);
        return out;
    }

    // ---------------------------------------------------------------- receiving

    static void onRequest(ServerPlayer player, String app, String json) {
        Handler handler = HANDLERS.get(app);
        if (handler == null) {
            // The client has the app but this server doesn't: say so rather than leave the page waiting.
            send(player, app, error(idOf(json), "This server doesn't have " + app));
            return;
        }
        handler.onRequest(player, json);
    }

    @Nullable
    private static String idOf(String json) {
        try {
            JsonObject request = JsonParser.parseString(json).getAsJsonObject();
            return request.has("id") && request.get("id").isJsonPrimitive() ? request.get("id").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Large requests (sent in pieces, up to 8 MB to unpack and read) a player may still make: an import or a big undo is
     * now and then, so few.
     */
    private static final Map<UUID, double[]> LARGE_ALLOWANCE = new ConcurrentHashMap<>();
    private static final double LARGE_BURST = 4;
    private static final double LARGE_PER_SECOND = 0.5;

    private static boolean takeLarge(ServerPlayer player) {
        long now = System.nanoTime();
        double[] state = LARGE_ALLOWANCE.computeIfAbsent(player.getUUID(), k -> new double[] {LARGE_BURST, now});
        state[0] = Math.min(LARGE_BURST, state[0] + (now - state[1]) / 1e9 * LARGE_PER_SECOND);
        state[1] = now;
        if (state[0] < 1) {
            return false;
        }
        state[0]--;
        return true;
    }

    /** Pieces of large requests being received, by player, then by message id. */
    private static final Map<UUID, Map<Integer, byte[][]>> PARTS = new ConcurrentHashMap<>();

    /**
     * A piece of a large request. Once all of it is in, it is handled as any request. At most a few are put together at
     * once per player (older ones dropped), each within the piece and size limits.
     */
    static void onRequestPart(ServerPlayer player, RelayPayloads.RequestPart part) {
        String id = part.id().isEmpty() ? null : part.id();
        if (part.total() < 1 || part.total() > RelayPayloads.MAX_PARTS || part.index() < 0 || part.index() >= part.total()) {
            send(player, part.app(), error(id, "Request too large"));
            return;
        }
        Map<Integer, byte[][]> mine = PARTS.computeIfAbsent(player.getUUID(), k -> new java.util.LinkedHashMap<>());
        byte[][] pieces;
        boolean refused = false;
        synchronized (mine) {
            pieces = mine.get(part.message());
            if (pieces == null) {
                // Charged once, at its first piece, before any work: a refused one keeps an empty entry so its other
                // pieces are dropped.
                refused = !takeLarge(player);
                pieces = refused ? new byte[0][] : new byte[part.total()][];
                mine.put(part.message(), pieces);
            }
            while (mine.size() > 4) {
                mine.remove(mine.keySet().iterator().next());
            }
        }
        if (refused) {
            send(player, part.app(), error(id, "Too many requests — slow down"));
            return;
        }
        if (pieces.length != part.total()) {
            return;
        }
        pieces[part.index()] = part.data();
        for (byte[] piece : pieces) {
            if (piece == null) {
                return;
            }
        }
        synchronized (mine) {
            mine.remove(part.message());
        }
        String json;
        try {
            json = RelayPayloads.join(java.util.Arrays.asList(pieces), RelayPayloads.MAX_REQUEST_BYTES);
        } catch (java.util.zip.DataFormatException e) {
            send(player, part.app(), error(id, "Request too large"));
            return;
        }
        onRequest(player, part.app(), json);
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PARTS.remove(event.getEntity().getUUID());
        LARGE_ALLOWANCE.remove(event.getEntity().getUUID());
    }

    static void onServerStopped(ServerStoppedEvent event) {
        PARTS.clear();
        LARGE_ALLOWANCE.clear();
    }
}
