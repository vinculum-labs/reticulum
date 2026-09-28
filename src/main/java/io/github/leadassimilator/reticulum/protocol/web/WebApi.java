package io.github.leadassimilator.reticulum.protocol.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;

import io.github.leadassimilator.reticulum.engine.Metrics;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.Security;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the web UI protocol. The browser talks JSON to the player's own game client, which passes each
 * request here unchanged; every request is checked against the player's permissions exactly like an in-game action.
 *
 * <p>Requests: {@code {"id": "...", "op": "networks" | "subscribe" | "unsubscribe" | "edit" | "history" | "checkFilter", ...}}.
 * Replies: {@code {"type": "response", "id": "...", "ok": true|false, ...}}. Pushes to subscribers:
 * {@code {"type": "snapshot", "network": {...}}} when a network changes, and {@code {"type": "metrics", ...}} each second.
 */
public final class WebApi {
    /** Per player: subscribed networks and the revision last sent. */
    private static final Map<UUID, Map<UUID, Integer>> SUBSCRIPTIONS = new HashMap<>();
    /** Players whose editor shows the network list, and the list they were last sent. */
    private static final Map<UUID, String> LIST_LISTENERS = new HashMap<>();
    private static int nextMessage;

    private WebApi() {
    }

    // ---------------------------------------------------------------- transport

    public static void onRequest(ServerPlayer player, String json) {
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            send(player, error(null, "Malformed request"));
            return;
        }
        String id = request.has("id") ? request.get("id").getAsString() : null;
        try {
            send(player, handle(player, request, id));
        } catch (RequestException e) {
            send(player, error(id, e.getMessage()));
        } catch (RuntimeException e) {
            io.github.leadassimilator.reticulum.Reticulum.LOGGER.error("Web request failed: {}", json, e);
            send(player, error(id, "Internal error: " + e.getMessage()));
        }
    }

    static void send(ServerPlayer player, JsonObject message) {
        for (WebPayloads.Chunk chunk : WebPayloads.split(nextMessage++, message.toString())) {
            PacketDistributor.sendToPlayer(player, chunk);
        }
    }

    public static final class RequestException extends Exception {
        RequestException(String message) {
            super(message);
        }
    }

    private static JsonObject ok(@Nullable String id) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "response");
        if (id != null) {
            out.addProperty("id", id);
        }
        out.addProperty("ok", true);
        return out;
    }

    private static JsonObject error(@Nullable String id, String message) {
        JsonObject out = ok(id);
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }

    // ---------------------------------------------------------------- requests

    public static JsonObject handle(ServerPlayer player, JsonObject request, @Nullable String id) throws RequestException {
        MinecraftServer server = player.server;
        String op = string(request, "op");
        return switch (op) {
            case "networks" -> {
                JsonArray list = networkList(server, player);
                LIST_LISTENERS.put(player.getUUID(), list.toString());
                JsonObject out = ok(id);
                out.add("networks", list);
                out.addProperty("player", player.getGameProfile().getName());
                yield out;
            }
            case "subscribe" -> {
                Network network = viewable(player, request);
                SUBSCRIPTIONS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(network.id(), network.revision());
                JsonObject out = ok(id);
                out.add("network", Snapshots.full(server, network, Security.role(network, player)));
                yield out;
            }
            case "unsubscribe" -> {
                Map<UUID, Integer> subs = SUBSCRIPTIONS.get(player.getUUID());
                if (subs != null) {
                    subs.remove(uuid(request, "network"));
                }
                yield ok(id);
            }
            case "edit" -> {
                Network network = viewable(player, request);
                if (!Security.canEdit(network, player)) {
                    throw new RequestException("You don't have edit access to " + network.name());
                }
                JsonArray edits = request.getAsJsonArray("edits");
                if (edits == null) {
                    throw new RequestException("Missing 'edits'");
                }
                JsonObject out = ok(id);
                JsonArray created = new JsonArray();
                for (int i = 0; i < edits.size(); i++) {
                    try {
                        JsonElement result = Edits.apply(server, network, edits.get(i).getAsJsonObject());
                        created.add(result);
                    } catch (RequestException e) {
                        NetworkManager.changed(server, network);
                        throw new RequestException("Edit " + (i + 1) + ": " + e.getMessage());
                    }
                }
                NetworkManager.changed(server, network);
                // Push right away rather than on the next tick: a paused singleplayer server doesn't tick.
                pushSnapshots(server, network);
                out.add("results", created);
                yield out;
            }
            case "deleteNetwork" -> {
                Network network = viewable(player, request);
                if (!Security.has(network, player, io.github.leadassimilator.reticulum.security.Role.OWNER)) {
                    throw new RequestException("Only the owner can delete " + network.name());
                }
                NetworkManager.delete(server, network);
                yield ok(id);
            }
            case "history" -> {
                Network network = viewable(player, request);
                boolean perMinute = request.has("window") && "minute".equals(request.get("window").getAsString());
                long[] values = NetworkTicker.runtime(network.id()).metrics().history(uuid(request, "item"), perMinute);
                JsonObject out = ok(id);
                JsonArray array = new JsonArray();
                for (long v : values) {
                    array.add(v);
                }
                out.add("values", array);
                yield out;
            }
            case "checkFilter" -> {
                Network network = viewable(player, request);
                String expression = string(request, "expression");
                JsonObject out = ok(id);
                try {
                    Filters.items(expression, network.filters());
                    out.addProperty("valid", true);
                } catch (FilterException e) {
                    out.addProperty("valid", false);
                    out.addProperty("message", e.getMessage());
                }
                yield out;
            }
            default -> throw new RequestException("Unknown op '" + op + "'");
        };
    }

    /** The networks a player can see, as the editor's tabs show them. */
    private static JsonArray networkList(MinecraftServer server, ServerPlayer player) {
        JsonArray list = new JsonArray();
        for (Network network : NetworkManager.data(server).networks()) {
            if (Security.canView(network, player)) {
                list.add(Snapshots.summary(server, network, Security.role(network, player)));
            }
        }
        return list;
    }

    private static Network viewable(ServerPlayer player, JsonObject request) throws RequestException {
        Network network = NetworkManager.network(player.server, uuid(request, "network"));
        if (network == null || !Security.canView(network, player)) {
            throw new RequestException("No such network, or no access");
        }
        return network;
    }

    static String string(JsonObject json, String key) throws RequestException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new RequestException("Missing '" + key + "'");
        }
        return value.getAsString();
    }

    static UUID uuid(JsonObject json, String key) throws RequestException {
        try {
            return UUID.fromString(string(json, key));
        } catch (IllegalArgumentException e) {
            throw new RequestException("'" + key + "' is not an id");
        }
    }

    static <T> T decode(MinecraftServer server, Codec<T> codec, @Nullable JsonElement json, String what) throws RequestException {
        if (json == null) {
            throw new RequestException("Missing '" + what + "'");
        }
        return codec.parse(Snapshots.ops(server), json).getOrThrow(message -> new RequestException("Bad " + what + ": " + message));
    }

    // ---------------------------------------------------------------- edits

    /** Edit commands. Each returns what it created (an id), or JSON null. */
    static final class Edits {
        private Edits() {
        }

        static JsonElement apply(MinecraftServer server, Network network, JsonObject edit) throws RequestException {
            String type = string(edit, "type");
            switch (type) {
                case "createSheet" -> {
                    Sheet sheet = new Sheet(UUID.randomUUID(), string(edit, "name"));
                    network.sheets().add(sheet);
                    return new com.google.gson.JsonPrimitive(sheet.id().toString());
                }
                case "updateSheet" -> {
                    Sheet sheet = sheet(network, edit);
                    if (edit.has("name")) {
                        sheet.setName(string(edit, "name"));
                    }
                    if (edit.has("enabled")) {
                        sheet.setEnabled(edit.get("enabled").getAsBoolean());
                    }
                    if (edit.has("interval")) {
                        sheet.setInterval(Math.max(1, edit.get("interval").getAsInt()));
                    }
                }
                case "deleteSheet" -> network.sheets().remove(sheet(network, edit));
                case "addNode", "updateNode" -> {
                    Sheet sheet = sheet(network, edit);
                    Node node = decode(server, Node.CODEC, edit.get("node"), "node");
                    checkPorts(network, node);
                    if (type.equals("addNode")) {
                        if (sheet.node(node.id()) != null) {
                            throw new RequestException("A node with that id already exists");
                        }
                        sheet.add(node);
                    } else {
                        if (sheet.node(node.id()) == null) {
                            throw new RequestException("No such node");
                        }
                        sheet.replace(node);
                    }
                    return new com.google.gson.JsonPrimitive(node.id().toString());
                }
                case "removeNode" -> sheet(network, edit).remove(uuid(edit, "node"));
                case "moveNodes" -> {
                    Sheet sheet = sheet(network, edit);
                    for (JsonElement element : edit.getAsJsonArray("positions")) {
                        JsonObject p = element.getAsJsonObject();
                        Node node = sheet.node(uuid(p, "id"));
                        if (node != null) {
                            sheet.replace(new Node(node.id(), node.settings(), node.label(), node.enabled(),
                                    p.get("x").getAsFloat(), p.get("y").getAsFloat()));
                        }
                    }
                }
                case "connect" -> {
                    Sheet sheet = sheet(network, edit);
                    Wire wire = decode(server, Wire.CODEC, edit.get("wire"), "wire");
                    if (sheet.node(wire.from()) == null || sheet.node(wire.to()) == null) {
                        throw new RequestException("Wire ends must be nodes on the same sheet");
                    }
                    sheet.connect(wire);
                    return new com.google.gson.JsonPrimitive(wire.id().toString());
                }
                case "disconnect" -> sheet(network, edit).disconnect(uuid(edit, "wire"));
                case "renameDevice" -> device(server, network, edit).setName(edit.has("name") ? edit.get("name").getAsString() : null);
                case "renameFace" -> {
                    FaceKey face = decode(server, FaceKey.CODEC, edit.get("face"), "face");
                    device(server, network, edit).setFaceName(face, edit.has("name") ? edit.get("name").getAsString() : null);
                }
                case "deleteOrphan" -> {
                    GlobalPos pos = decode(server, GlobalPos.CODEC, edit.get("device"), "device");
                    if (!NetworkManager.deleteOrphan(server, network, pos)) {
                        throw new RequestException("Only orphaned or missing devices can be deleted");
                    }
                }
                case "setFilter" -> {
                    String name = string(edit, "name");
                    if (edit.has("expression") && !edit.get("expression").isJsonNull()) {
                        String expression = edit.get("expression").getAsString();
                        try {
                            Filters.items(expression, withTemplate(network, name, expression));
                        } catch (FilterException e) {
                            throw new RequestException(e.getMessage());
                        }
                        network.filters().put(name, expression);
                    } else {
                        network.filters().remove(name);
                    }
                    network.sheets().forEach(Sheet::touch);
                }
                case "setNetworkEnabled" -> network.setEnabled(edit.get("enabled").getAsBoolean());
                default -> throw new RequestException("Unknown edit '" + type + "'");
            }
            return com.google.gson.JsonNull.INSTANCE;
        }

        private static Map<String, String> withTemplate(Network network, String name, String expression) {
            Map<String, String> templates = new HashMap<>(network.filters());
            templates.put(name, expression);
            return templates;
        }

        private static Sheet sheet(Network network, JsonObject edit) throws RequestException {
            Sheet sheet = network.sheet(uuid(edit, "sheet"));
            if (sheet == null) {
                throw new RequestException("No such sheet");
            }
            return sheet;
        }

        private static DeviceRecord device(MinecraftServer server, Network network, JsonObject edit) throws RequestException {
            GlobalPos pos = decode(server, GlobalPos.CODEC, edit.get("device"), "device");
            DeviceRecord record = network.device(pos);
            if (record == null) {
                throw new RequestException("That block is not a device of this network");
            }
            return record;
        }

        /** Sources and sinks may only point at devices of this network. */
        private static void checkPorts(Network network, Node node) throws RequestException {
            GlobalPos device = switch (node.settings()) {
                case NodeSettings.DeviceSettings s -> s.device();
                default -> null;
            };
            if (device != null && network.device(device) == null) {
                throw new RequestException("That block is not a device of this network");
            }
        }
    }

    // ---------------------------------------------------------------- pushes

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            pushNetworkLists(server);
        }
        if (SUBSCRIPTIONS.isEmpty() || server.getTickCount() % 5 != 0) {
            return;
        }
        boolean metricsDue = server.getTickCount() % 20 == 0;
        for (Map.Entry<UUID, Map<UUID, Integer>> entry : SUBSCRIPTIONS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            var subs = entry.getValue();
            for (UUID networkId : List.copyOf(subs.keySet())) {
                Network network = NetworkManager.network(server, networkId);
                if (network == null || !Security.canView(network, player)) {
                    subs.remove(networkId);
                    JsonObject gone = new JsonObject();
                    gone.addProperty("type", "removed");
                    gone.addProperty("network", networkId.toString());
                    send(player, gone);
                    continue;
                }
                if (subs.get(networkId) != network.revision()) {
                    subs.put(networkId, network.revision());
                    JsonObject push = new JsonObject();
                    push.addProperty("type", "snapshot");
                    push.add("network", Snapshots.full(server, network, Security.role(network, player)));
                    send(player, push);
                }
                if (metricsDue && NetworkTicker.isRunning(server, network)) {
                    send(player, metrics(network));
                }
            }
        }
    }

    /** Sends a fresh snapshot of the network to every subscriber now. */
    private static void pushSnapshots(MinecraftServer server, Network network) {
        for (Map.Entry<UUID, Map<UUID, Integer>> entry : SUBSCRIPTIONS.entrySet()) {
            Map<UUID, Integer> subs = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !subs.containsKey(network.id()) || !Security.canView(network, player)) {
                continue;
            }
            subs.put(network.id(), network.revision());
            JsonObject push = new JsonObject();
            push.addProperty("type", "snapshot");
            push.add("network", Snapshots.full(server, network, Security.role(network, player)));
            send(player, push);
        }
    }

    /** Sends the network list to editors whose list changed: a network was added, removed, renamed, or access changed. */
    private static void pushNetworkLists(MinecraftServer server) {
        for (Map.Entry<UUID, String> entry : LIST_LISTENERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            JsonArray list = networkList(server, player);
            String text = list.toString();
            if (!text.equals(entry.getValue())) {
                entry.setValue(text);
                JsonObject push = new JsonObject();
                push.addProperty("type", "networks");
                push.add("networks", list);
                send(player, push);
            }
        }
    }

    private static JsonObject metrics(Network network) {
        NetworkRuntime runtime = NetworkTicker.runtime(network.id());
        Metrics metrics = runtime.metrics();
        JsonObject out = new JsonObject();
        out.addProperty("type", "metrics");
        out.addProperty("network", network.id().toString());
        JsonObject values = new JsonObject();
        metrics.lastSecond().forEach((id, value) -> values.addProperty(id.toString(), value));
        out.add("perSecond", values);
        return out;
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIPTIONS.remove(event.getEntity().getUUID());
        LIST_LISTENERS.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SUBSCRIPTIONS.clear();
        LIST_LISTENERS.clear();
    }

    /** Used by the "Open editor" button on the controller; the client turns this into a tab switch. */
    public static Optional<Network> networkAt(MinecraftServer server, UUID id) {
        return Optional.ofNullable(NetworkManager.network(server, id));
    }

    /** Lets resource kinds be named in JSON the same way everywhere. */
    static ResourceKind resource(String name) throws RequestException {
        for (ResourceKind kind : ResourceKind.values()) {
            if (kind.getSerializedName().equals(name)) {
                return kind;
            }
        }
        throw new RequestException("Unknown resource '" + name + "'");
    }
}
