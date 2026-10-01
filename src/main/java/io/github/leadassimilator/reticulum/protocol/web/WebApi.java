package io.github.leadassimilator.reticulum.protocol.web;

import java.util.ArrayList;
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
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;

import io.github.leadassimilator.reticulum.security.NetworkAdmin;
import io.github.leadassimilator.reticulum.engine.Metrics;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Redstone;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Limits;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Passwords;
import io.github.leadassimilator.reticulum.security.Role;
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
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
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
                try {
                    for (int i = 0; i < edits.size(); i++) {
                        try {
                            if (!edits.get(i).isJsonObject()) {
                                throw new RequestException("not an edit");
                            }
                            created.add(Edits.apply(player, network, edits.get(i).getAsJsonObject()));
                        } catch (RequestException e) {
                            throw new RequestException("Edit " + (i + 1) + ": " + e.getMessage());
                        }
                    }
                } finally {
                    // Whatever went wrong part way, the edits before it are saved and shown.
                    NetworkManager.changed(server, network);
                    // Push right away rather than on the next tick: a paused singleplayer server doesn't tick.
                    pushSnapshots(server, network);
                }
                out.add("results", created);
                yield out;
            }
            case "deleteNetwork" -> {
                Network network = viewable(player, request);
                if (!Security.has(network, player, Role.OWNER)) {
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
            case "deviceContents" -> {
                Network network = viewable(player, request);
                GlobalPos device = decode(server, GlobalPos.CODEC, request.get("device"), "device");
                if (network.device(device) == null) {
                    throw new RequestException("That device isn't on " + network.name());
                }
                net.minecraft.server.level.ServerLevel level = server.getLevel(device.dimension());
                JsonObject out = level == null ? new JsonObject() : DeviceContents.read(level, device.pos(), string(request, "resource"));
                if (!out.has("entries")) {
                    out.add("entries", new JsonArray());
                }
                JsonObject response = ok(id);
                response.add("entries", out.get("entries"));
                yield response;
            }
            case "checkFilter" -> {
                Network network = viewable(player, request);
                String expression = string(request, "expression");
                JsonObject out = ok(id);
                try {
                    // Checked as a filter for the resource it is for (items when not said), since some terms only apply to one.
                    switch (request.has("resource") ? request.get("resource").getAsString() : "item") {
                        case "fluid" -> Filters.fluids(expression, network.filters());
                        case "chemical" -> {
                            if (io.github.leadassimilator.reticulum.integration.Mods.MEKANISM) {
                                Filters.chemicals(expression, network.filters());
                            }
                        }
                        default -> Filters.items(expression, network.filters());
                    }
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

        static JsonElement apply(ServerPlayer player, Network network, JsonObject edit) throws RequestException {
            MinecraftServer server = player.server;
            String type = string(edit, "type");
            switch (type) {
                case "importSheet" -> {
                    // A sheet pasted as JSON (from another world, say): new ids, the same devices by position.
                    Sheet pasted = decode(server, Sheet.CODEC, edit.get("sheet"), "sheet");
                    roomForSheet(network);
                    check(Limits.check(pasted));
                    String name = Limits.name(pasted.name());
                    Sheet sheet = pasted.copyWithNewIds(name == null ? "Imported sheet" : name);
                    network.sheets().add(sheet);
                    return new com.google.gson.JsonPrimitive(sheet.id().toString());
                }
                case "createSheet" -> {
                    roomForSheet(network);
                    String name = Limits.name(string(edit, "name"));
                    Sheet sheet = new Sheet(UUID.randomUUID(), name == null ? "Sheet " + (network.sheets().size() + 1) : name);
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
                    if (edit.has("folder")) {
                        sheet.setFolder(edit.get("folder").getAsString());
                    }
                    if (edit.has("redstone")) {
                        sheet.setRedstone(decode(server, Redstone.CODEC, edit.get("redstone"), "redstone"));
                    }
                    if (edit.has("subsheet")) {
                        sheet.setSubsheet(edit.get("subsheet").getAsBoolean());
                        network.sheets().forEach(Sheet::touch);
                    }
                }
                case "setFolderEnabled" -> {
                    String folder = Sheet.normalizeFolder(string(edit, "folder"));
                    if (edit.get("enabled").getAsBoolean()) {
                        network.disabledFolders().remove(folder);
                    } else {
                        network.disabledFolders().add(folder);
                    }
                }
                case "renameNetwork" -> network.setName(string(edit, "name"));
                case "setAccess" -> {
                    admin(network, player);
                    AccessMode mode = decode(server, AccessMode.CODEC, edit.get("mode"), "mode");
                    admin(() -> NetworkAdmin.setAccess(network, mode));
                }
                case "setPassword" -> {
                    admin(network, player);
                    String password = string(edit, "password");
                    admin(() -> NetworkAdmin.setPassword(network, password));
                }
                case "setMember" -> {
                    admin(network, player);
                    Role role = decode(server, Role.CODEC, edit.get("role"), "role");
                    GameProfile profile = profile(server, string(edit, "player"));
                    admin(() -> NetworkAdmin.setMember(network, profile, role));
                }
                case "removeMember" -> {
                    admin(network, player);
                    network.members().remove(uuid(edit, "id"));
                }
                case "deleteSheet" -> {
                    Sheet sheet = sheet(network, edit);
                    network.sheets().remove(sheet);
                    sheet.nodes().forEach(node -> forgetSignalMemory(network, node.id()));
                }
                case "addNode", "updateNode" -> {
                    Sheet sheet = sheet(network, edit);
                    Node node = decode(server, Node.CODEC, edit.get("node"), "node");
                    checkPorts(network, node);
                    check(Limits.check(node));
                    if (type.equals("addNode")) {
                        if (sheet.node(node.id()) != null) {
                            throw new RequestException("A node with that id already exists");
                        }
                        if (sheet.nodes().size() >= Limits.NODES_PER_SHEET) {
                            throw new RequestException("A sheet can have at most " + Limits.NODES_PER_SHEET + " nodes");
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
                case "removeNode" -> {
                    UUID node = uuid(edit, "node");
                    sheet(network, edit).remove(node);
                    forgetSignalMemory(network, node);
                }
                case "moveNodes" -> {
                    Sheet sheet = sheet(network, edit);
                    for (JsonElement element : edit.getAsJsonArray("positions")) {
                        JsonObject p = element.getAsJsonObject();
                        Node node = sheet.node(uuid(p, "id"));
                        if (node != null) {
                            sheet.replace(node.at(p.get("x").getAsFloat(), p.get("y").getAsFloat()));
                        }
                    }
                }
                case "connect" -> {
                    Sheet sheet = sheet(network, edit);
                    Wire wire = decode(server, Wire.CODEC, edit.get("wire"), "wire");
                    if (sheet.node(wire.from()) == null || sheet.node(wire.to()) == null) {
                        throw new RequestException("Wire ends must be nodes on the same sheet");
                    }
                    if (sheet.wires().size() >= Limits.WIRES_PER_SHEET) {
                        throw new RequestException("A sheet can have at most " + Limits.WIRES_PER_SHEET + " wires");
                    }
                    sheet.connect(wire);
                    return new com.google.gson.JsonPrimitive(wire.id().toString());
                }
                case "disconnect" -> sheet(network, edit).disconnect(uuid(edit, "wire"));
                case "orderWires" -> {
                    // A splitter's branches in a new order ("in order" and weights follow it).
                    List<UUID> ids = new ArrayList<>();
                    for (JsonElement id : edit.getAsJsonArray("wires")) {
                        try {
                            ids.add(UUID.fromString(id.getAsString()));
                        } catch (IllegalArgumentException e) {
                            throw new RequestException("'wires' holds something that isn't an id");
                        }
                    }
                    sheet(network, edit).orderWires(ids);
                }
                case "renameDevice" -> device(server, network, edit).setName(edit.has("name") ? edit.get("name").getAsString() : null);
                case "renameFace" -> {
                    FaceKey face = decode(server, FaceKey.CODEC, edit.get("face"), "face");
                    device(server, network, edit).setFaceName(face, edit.has("name") ? edit.get("name").getAsString() : null);
                }
                case "relinkDevice" -> {
                    GlobalPos from = decode(server, GlobalPos.CODEC, edit.get("from"), "from");
                    GlobalPos to = decode(server, GlobalPos.CODEC, edit.get("to"), "to");
                    if (!NetworkManager.relinkDevice(server, network, from, to)) {
                        throw new RequestException("Only an orphaned or missing device can be moved onto another device of this network");
                    }
                }
                case "deleteOrphan" -> {
                    GlobalPos pos = decode(server, GlobalPos.CODEC, edit.get("device"), "device");
                    if (!NetworkManager.deleteOrphan(server, network, pos)) {
                        throw new RequestException("Only orphaned or missing devices can be deleted");
                    }
                }
                case "setFilter" -> {
                    String name = Limits.name(string(edit, "name"));
                    if (name == null) {
                        throw new RequestException("A filter template needs a name");
                    }
                    if (edit.has("expression") && !edit.get("expression").isJsonNull()) {
                        String expression = edit.get("expression").getAsString();
                        if (!network.filters().containsKey(name) && network.filters().size() >= Limits.FILTER_TEMPLATES) {
                            throw new RequestException("A network can have at most " + Limits.FILTER_TEMPLATES + " filter templates");
                        }
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
                case "identify" -> {
                    io.github.leadassimilator.reticulum.network.Identify target = decode(server,
                            io.github.leadassimilator.reticulum.network.Identify.CODEC, edit, "identify");
                    boolean on = edit.get("on").getAsBoolean();
                    if (!io.github.leadassimilator.reticulum.network.Identifies.set(server, network, target, on) && on) {
                        throw new RequestException(io.github.leadassimilator.reticulum.network.Identifies.belongs(network, target)
                                ? "At most " + Limits.IDENTIFIES + " things can be identified at once"
                                : "That isn't a connector or device of this network");
                    }
                }
                case "clearIdentify" -> io.github.leadassimilator.reticulum.network.Identifies.clear(server, network);
                case "setNetworkRedstone" -> network.switches().setRedstone(decode(server, Redstone.CODEC, edit.get("redstone"), "redstone"));
                case "setFolderRedstone" -> {
                    String folder = Sheet.normalizeFolder(string(edit, "folder"));
                    Redstone redstone = decode(server, Redstone.CODEC, edit.get("redstone"), "redstone");
                    if (redstone.ignored()) {
                        network.switches().folderRedstone().remove(folder);
                    } else {
                        network.switches().folderRedstone().put(folder, redstone);
                    }
                }
                default -> throw new RequestException("Unknown edit '" + type + "'");
            }
            return com.google.gson.JsonNull.INSTANCE;
        }

        @FunctionalInterface
        private interface AdminChange {
            void run() throws NetworkAdmin.Refused;
        }

        /** Runs an access or membership change, turning a refusal into the editor's error. */
        private static void admin(AdminChange change) throws RequestException {
            try {
                change.run();
            } catch (NetworkAdmin.Refused e) {
                throw new RequestException(e.getMessage());
            }
        }

        /** What a removed latch or counter remembered goes with it (undo starts it from 0, as a new node). */
        private static void forgetSignalMemory(Network network, UUID node) {
            String key = node.toString();
            network.signalMemory().keySet().removeIf(k -> k.equals(key) || k.startsWith(key + "/"));
        }

        private static void roomForSheet(Network network) throws RequestException {
            if (network.sheets().size() >= Limits.SHEETS) {
                throw new RequestException("A network can have at most " + Limits.SHEETS + " sheets");
            }
        }

        /** Refuses an edit for the reason a limit check gave, if any. */
        private static void check(@Nullable String problem) throws RequestException {
            if (problem != null) {
                throw new RequestException(problem);
            }
        }

        private static void admin(Network network, ServerPlayer player) throws RequestException {
            if (!Security.canAdmin(network, player)) {
                throw new RequestException("Only the network's admins can change access and members");
            }
        }

        /** A player by name or UUID, online or remembered by the server. */
        private static GameProfile profile(MinecraftServer server, String name) throws RequestException {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            if (online != null) {
                return online.getGameProfile();
            }
            try {
                UUID id = UUID.fromString(name);
                ServerPlayer byId = server.getPlayerList().getPlayer(id);
                if (byId != null) {
                    return byId.getGameProfile();
                }
            } catch (IllegalArgumentException notAnId) {
                // a name, looked up below
            }
            var cache = server.getProfileCache();
            if (cache != null) {
                var found = cache.get(name);
                if (found.isPresent()) {
                    return found.get();
                }
            }
            throw new RequestException("No player called " + name + " has been on this server");
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
                case NodeSettings.LevelSettings s -> s.device();
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
                    sendSnapshot(server, player, network);
                }
                if (metricsDue && NetworkTicker.isRunning(server, network)) {
                    send(player, metrics(server, network));
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
            sendSnapshot(server, player, network);
        }
    }

    /** The network as this player may see it, pushed to their editor. */
    private static void sendSnapshot(MinecraftServer server, ServerPlayer player, Network network) {
        JsonObject push = new JsonObject();
        push.addProperty("type", "snapshot");
        push.add("network", Snapshots.full(server, network, Security.role(network, player)));
        send(player, push);
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

    private static JsonObject metrics(MinecraftServer server, Network network) {
        NetworkRuntime runtime = NetworkTicker.runtime(network.id());
        Metrics metrics = runtime.metrics();
        JsonObject out = new JsonObject();
        out.addProperty("type", "metrics");
        out.addProperty("network", network.id().toString());
        JsonObject values = new JsonObject();
        metrics.lastSecond().forEach((id, value) -> values.addProperty(id.toString(), value));
        out.add("perSecond", values);
        JsonObject fiveMinutes = new JsonObject();
        metrics.lastFiveMinutes().forEach((id, value) -> fiveMinutes.addProperty(id.toString(), value));
        out.add("fiveMinutes", fiveMinutes);
        JsonObject idle = new JsonObject();
        metrics.ticksSinceMoved(runtime.ticks()).forEach((id, value) -> idle.addProperty(id.toString(), value));
        out.add("ticksSinceMoved", idle);
        JsonObject statuses = new JsonObject();
        runtime.statuses().forEach((id, status) -> statuses.addProperty(id.toString(), status.key()));
        out.add("status", statuses);
        JsonObject signals = new JsonObject();
        Map<UUID, String> pins = new HashMap<>();
        for (Sheet sheet : network.sheets()) {
            CompiledSheet compiled = runtime.compiledIfPresent(sheet.id());
            if (compiled != null) {
                pins.putAll(compiled.pinKeys());
            }
        }
        runtime.signalValues().forEach((id, value) -> signals.addProperty(pins.getOrDefault(id, id.toString()), value));
        out.add("signals", signals);
        JsonObject named = new JsonObject();
        runtime.networkSignals().forEach(named::addProperty);
        out.add("networkSignals", named);
        JsonObject me = new JsonObject();
        runtime.meUsage().forEach((pin, usage) -> {
            JsonObject port = new JsonObject();
            port.addProperty("connected", usage.connected());
            port.addProperty("through", usage.through());
            port.addProperty("used", usage.used());
            port.addProperty("max", usage.max());
            me.add(pin, port);
        });
        out.add("me", me);
        out.add("power", powerJson(runtime.powerState(server, network)));
        long[] tick = runtime.tickTimeStats();
        JsonObject tickJson = new JsonObject();
        tickJson.addProperty("average", tick[0] / 1e6);
        tickJson.addProperty("longest", tick[1] / 1e6);
        out.add("tick", tickJson);
        JsonArray sheetsOff = new JsonArray();
        runtime.sheetsOff().forEach(id -> sheetsOff.add(id.toString()));
        out.add("sheetsOff", sheetsOff);
        return out;
    }

    static JsonObject powerJson(NetworkRuntime.PowerState state) {
        JsonObject power = new JsonObject();
        power.addProperty("enabled", state.enabled());
        power.addProperty("powered", state.powered());
        power.addProperty("stored", state.stored());
        power.addProperty("capacity", state.capacity());
        power.addProperty("upkeep", Math.round(state.upkeep() * 100) / 100.0);
        return power;
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
}
