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

import io.github.leadassimilator.reticulum.filter.FilterRefs;
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
    /** Edits only taken alone in a batch: what they change can't be put back if a later edit is refused. */
    private static final java.util.Set<String> ALONE = java.util.Set.of("relinkDevice", "deleteOrphan", "setAccess", "setPassword", "setMember",
            "removeMember", "addChart", "removeChart");

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
        JsonElement rawId = request.get("id");
        String id = rawId != null && rawId.isJsonPrimitive() ? rawId.getAsString() : null;
        if (!allowance(player)) {
            send(player, error(id, "Too many requests — slow down"));
            return;
        }
        try {
            send(player, handle(player, request, id));
        } catch (RequestException e) {
            send(player, error(id, e.getMessage()));
        } catch (IllegalStateException | UnsupportedOperationException | ClassCastException | NumberFormatException | JsonParseException e) {
            // A field of the wrong type (an object where a number goes, say): the request's fault, not the server's, so
            // no stack trace in the log for each one.
            send(player, error(id, "Malformed request: a field has the wrong type"));
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            io.github.leadassimilator.reticulum.Reticulum.LOGGER.error("Web request failed: {}", json, e);
            send(player, error(id, "Internal error: " + e.getMessage()));
        }
    }

    /** Requests a player may still make right now, refilled over time: far more than editing needs, but bounded. */
    private static final Map<UUID, double[]> ALLOWANCE = new HashMap<>();
    private static final double BURST = 60;
    private static final double PER_SECOND = 20;

    /** Takes one request from the player's allowance (each subscribe or edit makes the server build and send snapshots). */
    private static boolean allowance(ServerPlayer player) {
        long now = System.nanoTime();
        double[] state = ALLOWANCE.computeIfAbsent(player.getUUID(), k -> new double[] {BURST, now});
        state[0] = Math.min(BURST, state[0] + (now - state[1]) / 1e9 * PER_SECOND);
        state[1] = now;
        if (state[0] < 1) {
            return false;
        }
        state[0]--;
        return true;
    }

    static void send(ServerPlayer player, JsonObject message) {
        String text = message.toString();
        // Past what the editor takes (a huge network's snapshot), it would be dropped there without a word: an answer
        // says so instead, and a push is left out (the editor keeps what it has).
        if (text.length() > WebPayloads.MAX_MESSAGE_BYTES / 3 && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > WebPayloads.MAX_MESSAGE_BYTES) {
            if (!message.has("id")) {
                io.github.leadassimilator.reticulum.Reticulum.LOGGER.warn("A {} for the editor was too large to send ({} characters)",
                        message.has("type") ? message.get("type").getAsString() : "message", text.length());
                return;
            }
            text = error(message.get("id").getAsString(), "That is too large for the editor to show").toString();
        }
        for (WebPayloads.Chunk chunk : WebPayloads.split(nextMessage++, text)) {
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
                SUBSCRIPTIONS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(network.id(), stamp(server, network, player, true));
                JsonObject out = ok(id);
                out.add("network", Snapshots.full(server, network, player));
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
                if (edits.isEmpty()) {
                    // Nothing to change: no save, no snapshot pushed to every subscriber.
                    JsonObject none = ok(id);
                    none.add("results", new JsonArray());
                    yield none;
                }
                JsonObject out = ok(id);
                JsonArray created = new JsonArray();
                // All or nothing: the editor counts a refused batch (an undo, an import) as not made at all. Edits to
                // what a checkpoint doesn't hold (device records, access, members) come on their own.
                if (edits.size() > 1) {
                    for (JsonElement e : edits) {
                        String type = e.isJsonObject() && e.getAsJsonObject().has("type") ? e.getAsJsonObject().get("type").getAsString() : "";
                        if (ALONE.contains(type)) {
                            throw new RequestException("'" + type + "' has to be sent on its own");
                        }
                    }
                }
                Network.Checkpoint before = network.checkpoint();
                try {
                    for (int i = 0; i < edits.size(); i++) {
                        try {
                            if (!edits.get(i).isJsonObject()) {
                                throw new RequestException("not an edit");
                            }
                            created.add(Edits.apply(player, network, edits.get(i).getAsJsonObject()));
                        } catch (RequestException e) {
                            // Which edit only when there were several: alone, "Edit 1:" says nothing.
                            throw edits.size() > 1 ? new RequestException("Edit " + (i + 1) + ": " + e.getMessage()) : e;
                        }
                    }
                } catch (RequestException | RuntimeException e) {
                    before.restore();
                    io.github.leadassimilator.reticulum.network.Identifies.syncAll(server);
                    throw e;
                } finally {
                    // Saved and shown either way, with the tree put right.
                    network.ensureTree();
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
                UUID item = uuid(request, "item");
                var metrics = NetworkTicker.runtime(network.id()).metrics();
                long[] values = metrics.history(item, perMinute);
                JsonObject out = ok(id);
                // When the newest value's second or minute ended (epoch seconds): the same on every poll until the next,
                // where the browser's clock at each poll shifted the chart by up to a minute.
                out.addProperty("newest", System.currentTimeMillis() / 1000 - (perMinute ? metrics.secondsIntoMinute(item) : 0));
                JsonArray array = new JsonArray();
                for (long v : values) {
                    array.add(v);
                }
                out.add("values", array);
                yield out;
            }
            case "setSwitch" -> {
                // Not an edit (nothing in the network changes, no undo): where a dashboard switch stands, as a latch keeps.
                Network network = viewable(player, request);
                if (!Security.canEdit(network, player)) {
                    throw new RequestException("You don't have edit access to " + network.name());
                }
                Sheet sheet = network.sheet(uuid(request, "sheet"));
                Node node = sheet == null ? null : sheet.node(uuid(request, "node"));
                if (node == null || !(node.settings() instanceof NodeSettings.SwitchSettings toggle)) {
                    throw new RequestException("That switch is gone");
                }
                JsonElement v = request.get("value");
                if (v != null && !(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber())) {
                    throw new RequestException("A switch is set to a number");
                }
                long value = v == null ? 0 : v.getAsLong();
                if (!toggle.allows(value)) {
                    throw new RequestException("That switch can't be set to " + value);
                }
                // Which one: on its sheet, or in one blueprint instance (by the id it runs under there).
                JsonElement r = request.get("run");
                if (r != null && !r.isJsonNull() && !(r.isJsonPrimitive() && r.getAsJsonPrimitive().isString())) {
                    throw new RequestException("Which switch: an id");
                }
                String run = r != null && !r.isJsonNull() ? r.getAsString() : node.id().toString();
                if (!Edits.running(network, sheet, node).contains(run)) {
                    throw new RequestException("That switch isn't placed there");
                }
                if (value == 0) {
                    network.signalMemory().remove(run);
                } else {
                    network.signalMemory().put(run, value);
                }
                NetworkManager.data(server).setDirty();
                // Stopped, no live values come: editors see the flip in a fresh snapshot (it carries what is remembered).
                if (!NetworkTicker.isRunning(server, network)) {
                    pushSnapshots(server, network);
                }
                yield ok(id);
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
                        // A template: fine when items, fluids or chemicals take it (as setFilter checks).
                        case "any" -> Filters.checkAny(expression, network.filters());
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

    static boolean bool(JsonObject json, String key) throws RequestException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new RequestException("Missing '" + key + "'");
        }
        return value.getAsBoolean();
    }

    static JsonArray array(JsonObject json, String key) throws RequestException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new RequestException("Missing '" + key + "'");
        }
        return value.getAsJsonArray();
    }

    /** A text field that may be left out or null (both: none). */
    @Nullable
    static String optionalString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
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
                    // A sheet pasted as JSON (from another world, say): new ids, the same devices by position, placed
                    // in the parent asked for (the root when none). Sheet nodes in it are dropped: their sheets aren't here.
                    Sheet pasted = decode(server, Sheet.CODEC, edit.get("sheet"), "sheet");
                    roomForSheet(network);
                    check(Limits.check(pasted));
                    String name = Limits.name(pasted.name());
                    Sheet sheet = pasted.copyWithNewIds(name == null ? "Imported sheet" : name);
                    sheet.setRoot(false);
                    network.sheets().add(sheet);
                    // A device of this network keeps its one node: pasting a sheet of this world again is refused.
                    for (Node node : sheet.nodes()) {
                        checkHome(network, sheet, node, null);
                    }
                    // A placed blueprint whose blueprint isn't on this network (or that would hold the sheet it's on) is
                    // dropped, with its wires, as the editor says when importing; it would only show an error.
                    for (Node node : List.copyOf(sheet.nodes())) {
                        try {
                            checkInstance(network, sheet, node);
                        } catch (RequestException missing) {
                            sheet.remove(node.id());
                        }
                    }
                    if (!sheet.blueprint()) {
                        place(network, sheet, parentOf(network, edit), edit);
                    }
                    return new com.google.gson.JsonPrimitive(sheet.id().toString());
                }
                case "createSheet" -> {
                    // A new sheet, placed as a node in its parent (the root when none is given) where the editor asks.
                    roomForSheet(network);
                    String name = Limits.name(string(edit, "name"));
                    Sheet sheet = new Sheet(UUID.randomUUID(), name == null ? "Sheet " + network.sheets().size() : name);
                    if (edit.has("blueprint") && edit.get("blueprint").getAsBoolean()) {
                        sheet.setBlueprint(true);
                        network.sheets().add(sheet);
                    } else {
                        Sheet parent = parentOf(network, edit);
                        network.sheets().add(sheet);
                        place(network, sheet, parent, edit);
                    }
                    return new com.google.gson.JsonPrimitive(sheet.id().toString());
                }
                case "moveSheet" -> {
                    // Into another parent: its node there (same id, switch and label), wires on the old one dropped.
                    Sheet sheet = sheet(network, edit);
                    Sheet parent = parentOf(network, edit);
                    Network.Placement placement = network.placement(sheet.id());
                    if (sheet.root() || placement == null) {
                        throw new RequestException("Only a sheet in the tree can move");
                    }
                    if (parent == sheet || network.descendants(sheet).contains(parent)) {
                        throw new RequestException("A sheet can't go inside itself");
                    }
                    if (parent.blueprint()) {
                        throw new RequestException("Sheets can't go inside a blueprint");
                    }
                    deepEnough(network, parent, network.height(sheet));
                    if (parent != placement.parent()) {
                        if (parent.nodes().size() >= Limits.NODES_PER_SHEET) {
                            throw new RequestException("A sheet can have at most " + Limits.NODES_PER_SHEET + " nodes");
                        }
                        placement.parent().remove(placement.node().id());
                        float x = edit.has("x") ? coord(edit, "x") : placement.node().x();
                        float y = edit.has("y") ? coord(edit, "y") : placement.node().y();
                        parent.add(placement.node().at(x, y));
                    }
                }
                case "updateSheet" -> {
                    Sheet sheet = sheet(network, edit);
                    if (edit.has("name")) {
                        if (sheet.root()) {
                            network.setName(string(edit, "name")); // the root goes by the network's name
                        } else {
                            sheet.setName(string(edit, "name"));
                        }
                    }
                    if (edit.has("enabled")) {
                        // Its sheet node's switch (the network's, for the root).
                        network.setSheetEnabled(sheet, edit.get("enabled").getAsBoolean());
                    }
                    if (edit.has("interval")) {
                        sheet.setInterval(Math.max(1, edit.get("interval").getAsInt()));
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
                    admin(() -> NetworkAdmin.setPassword(network, player.getUUID(), password));
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
                case "deleteSheet" -> deleteSheet(server, network, sheet(network, edit));
                case "makeBlueprint" -> {
                    Sheet made = makeBlueprint(server, network, sheet(network, edit), edit.has("keep") && edit.get("keep").getAsBoolean());
                    return new com.google.gson.JsonPrimitive(made.id().toString());
                }
                case "detachInstance" -> {
                    Sheet parent = sheet(network, edit);
                    Node node = parent.node(uuid(edit, "node"));
                    if (node == null) {
                        throw new RequestException("No such node");
                    }
                    return new com.google.gson.JsonPrimitive(detachInstance(network, parent, node).id().toString());
                }
                case "duplicateBlueprint" -> {
                    Sheet blueprint = sheet(network, edit);
                    if (!blueprint.blueprint()) {
                        throw new RequestException("Only a blueprint can be duplicated");
                    }
                    roomForSheet(network);
                    Sheet copy = blueprint.copyWithNewIds(Limits.name(blueprint.name() + " (copy)"));
                    network.sheets().add(copy);
                    return new com.google.gson.JsonPrimitive(copy.id().toString());
                }
                case "addNode", "updateNode" -> {
                    Sheet sheet = sheet(network, edit);
                    Node sent = decode(server, Node.CODEC, edit.get("node"), "node");
                    // A blueprint placed inside a blueprint holds no blocks: each placement of the outer one fills its slots.
                    Node node = sheet.blueprint() && sent.settings() instanceof NodeSettings.InstanceSettings placed && !placed.bindings().isEmpty()
                            ? sent.with(new NodeSettings.InstanceSettings(placed.sheet(), Map.of())) : sent;
                    checkPorts(network, sheet, node);
                    checkSlotRead(sheet, node);
                    checkSheetNode(network, sheet, node, type.equals("addNode"));
                    checkInstance(network, sheet, node);
                    checkHome(network, sheet, node, type.equals("updateNode") ? sheet.node(node.id()) : null);
                    check(Limits.check(node));
                    if (type.equals("addNode")) {
                        // Sheets compile into one graph: a node id must be unique across the network.
                        if (network.sheets().stream().anyMatch(s -> s.node(node.id()) != null)) {
                            throw new RequestException("A node with that id already exists");
                        }
                        if (sheet.nodes().size() >= Limits.NODES_PER_SHEET) {
                            throw new RequestException("A sheet can have at most " + Limits.NODES_PER_SHEET + " nodes");
                        }
                        sheet.add(node);
                    } else {
                        Node old = sheet.node(node.id());
                        if (old == null) {
                            throw new RequestException("No such node");
                        }
                        // Another kind under the same id: what the old one remembered (a counter's count) goes, or a
                        // Switch could come up "on" from it.
                        // Likewise a placed blueprint switched to another blueprint: the old one's contents go.
                        boolean otherBlueprint = old.settings() instanceof NodeSettings.InstanceSettings was
                                && node.settings() instanceof NodeSettings.InstanceSettings now && !was.sheet().equals(now.sheet());
                        if (otherBlueprint) {
                            forgetNode(network, sheet, old);
                        }
                        if (old.kind() != node.kind()) {
                            forgetNode(network, sheet, old);
                            // A pin no longer that pin: the wires on its sheet's node(s) go, as when it is removed.
                            dropHolderWires(network, sheet, old);
                        }
                        sheet.replace(node);
                    }
                    return new com.google.gson.JsonPrimitive(node.id().toString());
                }
                case "removeNode" -> {
                    UUID id = uuid(edit, "node");
                    Sheet sheet = sheet(network, edit);
                    Node node = sheet.node(id);
                    if (node != null && node.settings() instanceof NodeSettings.SheetSettings child && network.sheet(child.sheet()) != null) {
                        // A sheet node goes with its sheet and everything in it.
                        deleteSheet(server, network, network.sheet(child.sheet()));
                    }
                    if (node != null) {
                        forgetNode(network, sheet, node);
                        dropHolderWires(network, sheet, node);
                    }
                    sheet.remove(id);
                }
                case "moveNodes" -> {
                    Sheet sheet = sheet(network, edit);
                    for (JsonElement element : array(edit, "positions")) {
                        JsonObject p = element.getAsJsonObject();
                        Node node = sheet.node(uuid(p, "id"));
                        if (node != null) {
                            sheet.replace(node.at(coord(p, "x"), coord(p, "y")));
                        }
                    }
                }
                case "connect" -> {
                    Sheet sheet = sheet(network, edit);
                    Wire wire = decode(server, Wire.CODEC, edit.get("wire"), "wire");
                    if (sheet.node(wire.from()) == null || sheet.node(wire.to()) == null) {
                        throw new RequestException("Wire ends must be nodes on the same sheet");
                    }
                    if (wire.from().equals(wire.to())) {
                        throw new RequestException("A wire can't go from a node back into itself");
                    }
                    // Sheets compile into one graph: ids must be unique across the network, not only on a sheet.
                    if (network.sheets().stream().anyMatch(s -> s.wires().stream().anyMatch(w -> w.id().equals(wire.id())))) {
                        throw new RequestException("A wire with that id already exists");
                    }
                    if (sheet.wires().stream().anyMatch(w -> w.from().equals(wire.from()) && w.output() == wire.output() && w.to().equals(wire.to())
                            && w.input() == wire.input())) {
                        throw new RequestException("Those two are already wired together");
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
                    for (JsonElement id : array(edit, "wires")) {
                        try {
                            ids.add(UUID.fromString(id.getAsString()));
                        } catch (IllegalArgumentException e) {
                            throw new RequestException("'wires' holds something that isn't an id");
                        }
                    }
                    sheet(network, edit).orderWires(ids);
                }
                case "renameDevice" -> device(server, network, edit).setName(optionalString(edit, "name"));
                case "renameFace" -> {
                    FaceKey face = decode(server, FaceKey.CODEC, edit.get("face"), "face");
                    device(server, network, edit).setFaceName(face, optionalString(edit, "name"));
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
                    // Not while a node or slot uses it: they would point at nothing. Relink it, or remove them first.
                    if (NetworkManager.inUse(network, pos)) {
                        throw new RequestException("Nodes still use this device: remove them, or relink it to another device, first");
                    }
                    if (!NetworkManager.deleteOrphan(server, network, pos)) {
                        throw new RequestException("Only orphaned or missing devices can be deleted");
                    }
                }
                case "addChart" -> {
                    io.github.leadassimilator.reticulum.network.ChartSeries chart = decode(server, io.github.leadassimilator.reticulum.network.ChartSeries.CODEC,
                            edit.get("chart"), "chart");
                    if (!chart.valid()) {
                        throw new RequestException("A chart needs an item, fluid or chemical, or a device and what it holds");
                    }
                    if (chart.device().isPresent() && network.device(chart.device().get()) == null) {
                        throw new RequestException("That block is not a device of this network");
                    }
                    if (network.charts().stream().anyMatch(c -> c.key().equals(chart.key()))) {
                        throw new RequestException("That is charted already");
                    }
                    if (network.charts().size() >= io.github.leadassimilator.reticulum.network.Limits.CHARTS) {
                        throw new RequestException("A network can chart at most " + io.github.leadassimilator.reticulum.network.Limits.CHARTS + " things");
                    }
                    network.charts().add(chart);
                }
                case "removeChart" -> {
                    String key = string(edit, "key");
                    network.charts().removeIf(c -> c.key().equals(key));
                }
                case "setFilter" -> {
                    String asked = string(edit, "name");
                    String name = Limits.name(asked);
                    if (name == null) {
                        throw new RequestException("A filter template needs a name");
                    }
                    // A new one named as a rename would allow (an existing one is only being changed or removed).
                    if (!network.filters().containsKey(name)) {
                        if (!name.equals(asked)) {
                            throw new RequestException("That name is too long, or starts or ends with a space");
                        }
                        if (name.contains("\"") || name.contains("\\")) {
                            throw new RequestException("A template name can't hold \" or \\");
                        }
                    }
                    if (edit.has("expression") && !edit.get("expression").isJsonNull()) {
                        String expression = edit.get("expression").getAsString();
                        if (!network.filters().containsKey(name) && network.filters().size() >= Limits.FILTER_TEMPLATES) {
                            throw new RequestException("A network can have at most " + Limits.FILTER_TEMPLATES + " filter templates");
                        }
                        try {
                            Filters.checkAny(expression, withTemplate(network, name, expression));
                        } catch (FilterException e) {
                            throw new RequestException(e.getMessage());
                        }
                        network.filters().put(name, expression);
                    } else {
                        network.filters().remove(name);
                    }
                    network.sheets().forEach(Sheet::touch);
                }
                case "renameFilter" -> {
                    // A template renamed where it is used too: other templates and every filter on every sheet and
                    // blueprint that names it. The reverse rename undoes it.
                    String from = string(edit, "from");
                    String asked = string(edit, "to");
                    String to = Limits.name(asked);
                    if (to == null) {
                        throw new RequestException("A filter template needs a name");
                    }
                    if (!to.equals(asked)) {
                        throw new RequestException("That name is too long, or starts or ends with a space");
                    }
                    if (to.contains("\"") || to.contains("\\")) {
                        throw new RequestException("A template name can't hold \" or \\");
                    }
                    if (!network.filters().containsKey(from)) {
                        throw new RequestException("There's no filter template @" + from);
                    }
                    if (!from.equals(to) && network.filters().containsKey(to)) {
                        throw new RequestException("There's already a filter template @" + to);
                    }
                    // Filters already naming the new name (a template since removed) would be taken over by the rename,
                    // and the reverse rename would then change them too: refused, so undo always restores.
                    boolean taken = network.filters().values().stream().anyMatch(e -> FilterRefs.mentions(e, to))
                            || network.sheets().stream().flatMap(s -> s.nodes().stream())
                                    .anyMatch(n -> FilterRefs.filters(n.settings()).stream().anyMatch(e -> FilterRefs.mentions(e, to)));
                    if (taken && !from.equals(to)) {
                        throw new RequestException("Filters already use @" + to + " (no such template): change those first, or pick another name");
                    }
                    java.util.function.UnaryOperator<String> renamed = e -> e == null ? null : FilterRefs.rename(e, from, to);
                    Map<String, String> templates = new java.util.LinkedHashMap<>();
                    network.filters().forEach((name, expression) -> templates.put(name.equals(from) ? to : name, renamed.apply(expression)));
                    network.filters().clear();
                    network.filters().putAll(templates);
                    for (Sheet sheet : network.sheets()) {
                        for (Node node : List.copyOf(sheet.nodes())) {
                            NodeSettings next = FilterRefs.rename(node.settings(), renamed);
                            if (!next.equals(node.settings())) {
                                sheet.replace(node.with(next));
                            }
                        }
                        sheet.touch();
                    }
                }
                case "setNetworkEnabled" -> network.setEnabled(bool(edit, "enabled"));
                case "identify" -> {
                    io.github.leadassimilator.reticulum.network.Identify target = decode(server,
                            io.github.leadassimilator.reticulum.network.Identify.CODEC, edit, "identify");
                    boolean on = bool(edit, "on");
                    if (!io.github.leadassimilator.reticulum.network.Identifies.set(server, network, target, on) && on) {
                        throw new RequestException(io.github.leadassimilator.reticulum.network.Identifies.belongs(network, target)
                                ? "At most " + Limits.IDENTIFIES + " things can be identified at once"
                                : "That isn't a connector or device of this network");
                    }
                }
                case "clearIdentify" -> io.github.leadassimilator.reticulum.network.Identifies.clear(server, network);
                case "setNetworkRedstone" -> network.switches().setRedstone(
                        decode(server, io.github.leadassimilator.reticulum.network.Switches.Redstone.CODEC, edit.get("redstone"), "redstone"));
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

        /**
         * What a removed latch or counter remembered goes with it (undo starts it from 0, as a new node), wherever it
         * runs: on its sheet, or under each instance's id for it when its sheet is a blueprint. A removed instance node takes
         * what everything inside it remembered.
         */
        private static void forgetNode(Network network, Sheet sheet, Node node) {
            for (String id : running(network, sheet, node)) {
                forgetRunning(network, id, node, 0);
            }
        }

        /** The ids a node runs under: its own on a sheet, or one per instance when its sheet is a blueprint. */
        static List<String> running(Network network, Sheet sheet, Node node) {
            return sheet.blueprint()
                    ? instances(network, sheet.id(), 0).stream().map(instance -> inInstance(instance, node.id())).toList()
                    : List.of(node.id().toString());
        }

        private static void forgetRunning(Network network, String id, Node node, int depth) {
            network.signalMemory().keySet().removeIf(k -> k.equals(id) || k.startsWith(id + "/"));
            if (depth < NESTING && node.settings() instanceof NodeSettings.InstanceSettings instance && network.sheet(instance.sheet()) instanceof Sheet blueprint) {
                for (Node inner : blueprint.nodes()) {
                    forgetRunning(network, inInstance(id, inner.id()), inner, depth + 1);
                }
            }
        }

        /**
         * A sheet pin's wires one level up go with it: on the sheet's node, or for a blueprint's pin on every instance node
         * of it. Left, they would hang off a handle that is gone, and reattach to a later pin given its slot.
         */
        private static void dropHolderWires(Network network, Sheet sheet, Node node) {
            if (!(node.settings() instanceof NodeSettings.SheetPinSettings pin) || pin.slot() < 0) {
                return;
            }
            List<Network.Placement> holders = new ArrayList<>();
            if (sheet.blueprint()) {
                for (Sheet on : network.sheets()) {
                    for (Node instance : on.nodes()) {
                        if (instance.settings() instanceof NodeSettings.InstanceSettings c && c.sheet().equals(sheet.id())) {
                            holders.add(new Network.Placement(on, instance));
                        }
                    }
                }
            } else if (network.placement(sheet.id()) != null) {
                holders.add(network.placement(sheet.id()));
            }
            for (Network.Placement placed : holders) {
                UUID holder = placed.node().id();
                for (Wire wire : List.copyOf(placed.parent().wires())) {
                    boolean on = pin.input() ? wire.to().equals(holder) && wire.input() == pin.slot() : wire.from().equals(holder) && wire.output() == pin.slot();
                    if (on) {
                        placed.parent().disconnect(wire.id());
                    }
                }
            }
        }

        /** How deep instances inside blueprints are followed (the engine refuses deeper ones). */
        private static final int NESTING = 8;

        /**
         * The ids a blueprint's contents run under: an instance's node id for each instance on a sheet, and for an instance inside
         * another blueprint, one per instance of that blueprint.
         */
        private static List<String> instances(Network network, UUID blueprint, int depth) {
            return instances(network, blueprint, depth, new java.util.HashSet<>());
        }

        /** {@code path}: the blueprints this one is reached through, so a loop (saved before loops were refused) ends. */
        private static List<String> instances(Network network, UUID blueprint, int depth, java.util.Set<UUID> path) {
            List<String> out = new ArrayList<>();
            if (depth >= NESTING || !path.add(blueprint)) {
                return out;
            }
            for (Sheet sheet : network.sheets()) {
                for (Node node : sheet.nodes()) {
                    if (node.settings() instanceof NodeSettings.InstanceSettings instance && instance.sheet().equals(blueprint)) {
                        if (!sheet.blueprint()) {
                            out.add(node.id().toString());
                        } else {
                            for (String outer : instances(network, sheet.id(), depth + 1, path)) {
                                out.add(inInstance(outer, node.id()));
                            }
                        }
                    }
                }
            }
            path.remove(blueprint);
            return out;
        }

        /** What a latch or counter remembered under {@code from} (and its "from/..." parts), moved to {@code to}. */
        private static void moveMemory(Network network, String from, String to) {
            Map<String, Long> memory = network.signalMemory();
            for (String key : List.copyOf(memory.keySet())) {
                if (key.equals(from) || key.startsWith(from + "/")) {
                    memory.put(to + key.substring(from.length()), memory.remove(key));
                }
            }
        }

        /**
         * Moves what a node remembers from one id it runs under to another, and, for a placed blueprint, what every node
         * in it remembers (their ids are made from the placed blueprint's), all the way down.
         */
        private static void moveRunning(Network network, String from, String to, Node node, int depth) {
            moveMemory(network, from, to);
            if (depth < NESTING && node.settings() instanceof NodeSettings.InstanceSettings instance && network.sheet(instance.sheet()) instanceof Sheet blueprint) {
                for (Node inner : blueprint.nodes()) {
                    moveRunning(network, inInstance(from, inner.id()), inInstance(to, inner.id()), inner, depth + 1);
                }
            }
        }

        private static String inInstance(String instance, String node) {
            return inInstance(instance, UUID.fromString(node));
        }

        /** The id a blueprint's node runs under in an instance (as the engine pastes it). */
        private static String inInstance(String instance, UUID node) {
            return UUID.nameUUIDFromBytes((instance + "/" + node).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
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
                // Offline (a member's role changed, or a removed member put back): known by id.
                var cache = server.getProfileCache();
                var known = cache == null ? java.util.Optional.<GameProfile>empty() : cache.get(id);
                if (known.isPresent()) {
                    return known.get();
                }
            } catch (IllegalArgumentException notAnId) {
                // a name, looked up below
            }
            // Players who have been on this server, from its saved list: asking the cache by name would look an unknown
            // one up online, on the server thread, stalling it.
            java.nio.file.Path users = server.getServerDirectory().resolve("usercache.json");
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(users)) {
                for (JsonElement entry : com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()) {
                    JsonObject user = entry.getAsJsonObject();
                    if (user.has("name") && user.has("uuid") && user.get("name").getAsString().equalsIgnoreCase(name)) {
                        return new GameProfile(UUID.fromString(user.get("uuid").getAsString()), user.get("name").getAsString());
                    }
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // No list (yet), or not one we can read: nobody found.
            }
            throw new RequestException("No player called " + name + " has been on this server");
        }

        private static Map<String, String> withTemplate(Network network, String name, String expression) {
            Map<String, String> templates = new HashMap<>(network.filters());
            templates.put(name, expression);
            return templates;
        }

        /** The parent sheet an edit names ("parent"), or the root when it names none. */
        private static Sheet parentOf(Network network, JsonObject edit) throws RequestException {
            if (!edit.has("parent") || edit.get("parent").isJsonNull()) {
                return network.root();
            }
            Sheet parent = network.sheet(uuid(edit, "parent"));
            if (parent == null || parent.blueprint()) {
                throw new RequestException("No such sheet to put it in");
            }
            return parent;
        }

        /** Places a sheet in {@code parent} as a sheet node, where the edit asks ("x", "y") or in the top left. */
        private static void place(Network network, Sheet sheet, Sheet parent, JsonObject edit) throws RequestException {
            deepEnough(network, parent, 0);
            if (parent.nodes().size() >= Limits.NODES_PER_SHEET) {
                throw new RequestException("A sheet can have at most " + Limits.NODES_PER_SHEET + " nodes");
            }
            float x = edit.has("x") ? coord(edit, "x") : 40;
            float y = edit.has("y") ? coord(edit, "y") : 40;
            parent.add(Node.of(new NodeSettings.SheetSettings(sheet.id())).at(x, y));
        }

        /** A Level sensor reading a slot: only on a blueprint, and a slot of that blueprint. */
        private static void checkSlotRead(Sheet sheet, Node node) throws RequestException {
            if (node.settings() instanceof NodeSettings.LevelSettings level && level.slot().isPresent()) {
                Node slot = sheet.node(level.slot().get());
                if (!sheet.blueprint() || slot == null || !(slot.settings() instanceof NodeSettings.DeviceSettings)) {
                    throw new RequestException("A Level sensor can only read a slot of its own blueprint");
                }
            }
        }

        /** Refuses putting a sheet {@code height} levels deep inside {@code parent} past the tree's depth limit. */
        private static void deepEnough(Network network, Sheet parent, int height) throws RequestException {
            if (network.depth(parent) + 1 + height > Limits.SHEET_DEPTH) {
                throw new RequestException("Sheets can go at most " + Limits.SHEET_DEPTH + " deep");
            }
        }

        /**
         * Sheet nodes come and go with their sheets (New sheet, Delete): one can't be added for a sheet already placed,
         * and an existing one can't be pointed at another sheet. Sheets can't go inside blueprints.
         */
        /**
         * A placed blueprint must place a blueprint, and never one that holds (in itself, or in blueprints placed in it)
         * the sheet it goes on: the engine refuses such loops, and walking them blows up.
         */
        private static void checkInstance(Network network, Sheet sheet, Node node) throws RequestException {
            if (!(node.settings() instanceof NodeSettings.InstanceSettings instance)) {
                return;
            }
            Sheet used = network.sheet(instance.sheet());
            if (used == null || !used.blueprint()) {
                throw new RequestException("A placed blueprint has to place a blueprint");
            }
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<Sheet> todo = new java.util.ArrayDeque<>(List.of(used));
            while (!todo.isEmpty()) {
                Sheet at = todo.pop();
                if (at.id().equals(sheet.id())) {
                    throw new RequestException("Blueprint '" + used.name() + "' would end up inside itself");
                }
                if (!seen.add(at.id())) {
                    continue;
                }
                for (Node inner : at.nodes()) {
                    if (inner.settings() instanceof NodeSettings.InstanceSettings nested && network.sheet(nested.sheet()) instanceof Sheet held) {
                        todo.push(held);
                    }
                }
            }
        }

        /** A position from the editor: a finite number (NaN or infinity would break every snapshot of the network). */
        private static float coord(JsonObject object, String key) throws RequestException {
            JsonElement e = object.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Float.isFinite(e.getAsFloat())) {
                throw new RequestException("A position must be a number");
            }
            return e.getAsFloat();
        }

        private static void checkSheetNode(Network network, Sheet sheet, Node node, boolean adding) throws RequestException {
            Node existing = sheet.node(node.id());
            if (!adding && existing != null && existing.settings() instanceof NodeSettings.SheetSettings
                    && !(node.settings() instanceof NodeSettings.SheetSettings)) {
                throw new RequestException("A sheet node stays one: delete it to delete its sheet");
            }
            if (!(node.settings() instanceof NodeSettings.SheetSettings child)) {
                return;
            }
            if (sheet.blueprint()) {
                throw new RequestException("Sheets can't go inside a blueprint");
            }
            if (adding || existing == null || !(existing.settings() instanceof NodeSettings.SheetSettings was) || !was.sheet().equals(child.sheet())) {
                throw new RequestException("Sheets are added with New sheet and moved with Move to");
            }
        }

        /**
         * A device lives on one sheet: a second device node for it, anywhere in the tree, is refused (naming where it
         * lives). A device several sheets need goes on the sheet holding them, its flows wired down through pins.
         * Blueprints are left alone: they are placed as instances.
         */
        /**
         * A device has one node. Only the devices this edit gives the node are checked ({@code before}: the node as it
         * was), so a node can still be edited when a merge left its device with two (the problem says so).
         */
        private static void checkHome(Network network, Sheet sheet, Node node, @Nullable Node before) throws RequestException {
            if (sheet.blueprint()) {
                return;
            }
            java.util.Set<net.minecraft.core.GlobalPos> had = before == null ? java.util.Set.of() : homes(network, before);
            if (node.settings() instanceof NodeSettings.DeviceSettings device) {
                if (!had.contains(device.device())) {
                    livesNowhere(network, device.device(), node.id());
                }
            } else if (node.settings() instanceof NodeSettings.InstanceSettings instance) {
                // A block filling a slot of an instance lives there, like a device node: once, in one slot.
                java.util.Set<net.minecraft.core.GlobalPos> seen = new java.util.HashSet<>();
                for (NodeSettings.Binding binding : io.github.leadassimilator.reticulum.graph.Blueprints.liveBindings(network, network.sheet(instance.sheet()), instance).values()) {
                    if (!seen.add(binding.device())) {
                        throw new RequestException("One block can't fill two slots");
                    }
                    if (!had.contains(binding.device())) {
                        livesNowhere(network, binding.device(), node.id());
                    }
                }
            }
        }

        /** The devices a node is the home of: a device node's device, the blocks filling an instance's slots. */
        private static java.util.Set<net.minecraft.core.GlobalPos> homes(Network network, Node node) {
            java.util.Set<net.minecraft.core.GlobalPos> out = new java.util.HashSet<>();
            if (node.settings() instanceof NodeSettings.DeviceSettings device) {
                out.add(device.device());
            } else if (node.settings() instanceof NodeSettings.InstanceSettings instance) {
                io.github.leadassimilator.reticulum.graph.Blueprints.liveBindings(network, network.sheet(instance.sheet()), instance).values().forEach(b -> out.add(b.device()));
            }
            return out;
        }

        private static void livesNowhere(Network network, net.minecraft.core.GlobalPos device, UUID except) throws RequestException {
            Network.Home home = network.home(device, except);
            if (home != null) {
                throw new RequestException("That device already lives in " + (home.sheet().root() ? home.sheet().name() : network.path(home.sheet()))
                        + ": a device has one node. Put it on the sheet that holds the ones needing it, and wire it down through pins.");
            }
        }

        /**
         * A blueprint made from a sheet in the tree: its nodes and wires (new ids), its device nodes as slots for their
         * kinds of blocks. Unless {@code keep}, the sheet becomes the blueprint's first instance, its slots filled with the
         * sheet's own blocks, so nothing stops running. Sheets with sheets inside them are refused for now.
         */
        private static Sheet makeBlueprint(MinecraftServer server, Network network, Sheet sheet, boolean keep) throws RequestException {
            if (sheet.root() || sheet.blueprint()) {
                throw new RequestException("Only a sheet in the tree can become a blueprint");
            }
            if (!network.descendants(sheet).isEmpty()) {
                throw new RequestException("A sheet with sheets inside it can't become a blueprint yet: move them out first");
            }
            java.util.Set<GlobalPos> devices = new java.util.HashSet<>();
            for (Node node : sheet.nodes()) {
                // Two slots filled by one block can't be edited after (one block fills one slot).
                if (node.settings() instanceof NodeSettings.DeviceSettings device && !devices.add(device.device())) {
                    throw new RequestException("A block has two nodes on this sheet: remove one first");
                }
            }
            roomForSheet(network);
            Map<UUID, UUID> ids = new HashMap<>();
            Sheet blueprint = sheet.copyWithNewIds(keep ? Limits.name(sheet.name() + " blueprint") : sheet.name(), ids);
            blueprint.setBlueprint(true);
            Map<UUID, NodeSettings.Binding> bindings = new HashMap<>();
            Map<GlobalPos, UUID> slotOf = new HashMap<>();
            for (Node original : sheet.nodes()) {
                if (original.settings() instanceof NodeSettings.DeviceSettings device) {
                    var record = network.device(device.device());
                    Node slot = blueprint.node(ids.get(original.id()));
                    blueprint.replace(slot.with(device.withBlock(record == null ? java.util.Optional.empty() : java.util.Optional.of(record.blockId()))));
                    bindings.put(slot.id(), new NodeSettings.Binding(device.device(), 0));
                    slotOf.putIfAbsent(device.device(), slot.id());
                }
            }
            // A blueprint placed on the sheet: its slots are the new blueprint's slots too, and its blocks this placement's
            // (each placement fills them with its own); the copy in the blueprint holds none.
            for (Node original : sheet.nodes()) {
                if (original.settings() instanceof NodeSettings.InstanceSettings placed) {
                    UUID copy = ids.get(original.id());
                    io.github.leadassimilator.reticulum.graph.Blueprints.liveBindings(network, network.sheet(placed.sheet()), placed)
                            .forEach((key, binding) -> bindings.put(io.github.leadassimilator.reticulum.graph.Blueprints.nested(copy, key), binding));
                    blueprint.replace(blueprint.node(copy).with(new NodeSettings.InstanceSettings(placed.sheet(), Map.of())));
                }
            }
            // A Level sensor reading one of the sheet's devices reads that device's slot: in each instance, its block.
            for (Node node : List.copyOf(blueprint.nodes())) {
                if (node.settings() instanceof NodeSettings.LevelSettings level && level.slot().isEmpty() && slotOf.containsKey(level.device())) {
                    blueprint.replace(node.with(level.readingSlot(slotOf.get(level.device()), level.device())));
                }
            }
            network.sheets().add(blueprint);
            Network.Placement placement = network.placement(sheet.id());
            if (!keep && placement != null) {
                // Its latches and counters keep what they remember, under the ids they now run under in the instance.
                for (Node original : sheet.nodes()) {
                    moveRunning(network, original.id().toString(), inInstance(placement.node().id().toString(), ids.get(original.id())), original, 0);
                }
                forget(server, network, sheet);
                // Same node, same wires: the blueprint's pins keep their slots.
                placement.parent().replace(placement.node().with(new NodeSettings.InstanceSettings(blueprint.id(), bindings)));
            }
            return blueprint;
        }

        /**
         * An instance turned back into a normal sheet in the tree: the blueprint's nodes and wires (new ids), each filled slot
         * a device node for its block; an empty one keeps the position it was laid out at.
         */
        private static Sheet detachInstance(Network network, Sheet parent, Node node) throws RequestException {
            if (!(node.settings() instanceof NodeSettings.InstanceSettings instance)) {
                throw new RequestException("That node isn't a placed blueprint");
            }
            if (parent.blueprint()) {
                throw new RequestException("A blueprint placed inside a blueprint can't become a sheet: sheets don't go in blueprints");
            }
            Sheet blueprint = network.sheet(instance.sheet());
            if (blueprint == null) {
                throw new RequestException("Its blueprint no longer exists");
            }
            roomForSheet(network);
            deepEnough(network, parent, 0);
            Map<UUID, NodeSettings.Binding> live = io.github.leadassimilator.reticulum.graph.Blueprints.liveBindings(network, blueprint, instance);
            // Its own slots become device nodes, so each needs a block; those of blueprints placed in it may stay empty.
            if (blueprint.nodes().stream().anyMatch(slot -> slot.settings() instanceof NodeSettings.DeviceSettings && !live.containsKey(slot.id()))) {
                throw new RequestException("Fill its empty slots first: an empty slot has no block of its own to become a device node");
            }
            Map<UUID, UUID> ids = new HashMap<>();
            Sheet sheet = blueprint.copyWithNewIds(node.label().orElse(blueprint.name()), ids);
            sheet.setBlueprint(false);
            for (Node inner : blueprint.nodes()) {
                Node made = sheet.node(ids.get(inner.id()));
                NodeSettings bound = io.github.leadassimilator.reticulum.graph.Blueprints.bound(inner, instance.bindings());
                if (bound == null && inner.settings() instanceof NodeSettings.DeviceSettings device) {
                    bound = device.withBlock(java.util.Optional.empty());
                } else if (bound == null && inner.settings() instanceof NodeSettings.LevelSettings level) {
                    // Reading a slot the blueprint no longer has: it reads where that slot was laid out.
                    bound = level.readingDevice(level.device(), level.faces());
                } else if (inner.settings() instanceof NodeSettings.InstanceSettings nested) {
                    // A blueprint placed inside: on a sheet now, it holds this placement's blocks for its slots itself.
                    bound = new NodeSettings.InstanceSettings(nested.sheet(),
                            io.github.leadassimilator.reticulum.graph.Blueprints.inner(network, instance.bindings(), inner.id(), network.sheet(nested.sheet())));
                }
                sheet.replace(made.with(bound));
                // What it remembered in the instance, now under its own id.
                moveRunning(network, inInstance(node.id().toString(), inner.id()), ids.get(inner.id()).toString(), inner, 0);
            }
            network.sheets().add(sheet);
            parent.replace(node.with(new NodeSettings.SheetSettings(sheet.id())));
            for (Node made : sheet.nodes()) {
                checkHome(network, sheet, made, null);
            }
            return sheet;
        }

        /** Takes a sheet out of the network, and what its latches and counters remembered. */
        private static void forget(MinecraftServer server, Network network, Sheet sheet) {
            for (Node node : sheet.nodes()) {
                forgetNode(network, sheet, node);
            }
            network.sheets().remove(sheet);
        }

        /** Deletes a sheet with every sheet inside it, and its sheet node. The root stays. */
        private static void deleteSheet(MinecraftServer server, Network network, Sheet sheet) throws RequestException {
            if (sheet.root()) {
                throw new RequestException("The network's top sheet can't be deleted");
            }
            Network.Placement placement = network.placement(sheet.id());
            List<Sheet> gone = new ArrayList<>(network.descendants(sheet));
            gone.add(sheet);
            for (Sheet each : gone) {
                forget(server, network, each);
            }
            if (placement != null) {
                placement.parent().remove(placement.node().id());
            }
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
        /** A node naming a device must name one of this network's; not on a blueprint, where it is only a layout position. */
        private static void checkPorts(Network network, Sheet sheet, Node node) throws RequestException {
            if (sheet.blueprint()) {
                return;
            }
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
                // Building a snapshot reads other mods' blocks: one that throws must not take the server down (as a tick
                // of the network itself wouldn't).
                try {
                    int stamp = stamp(server, network, player, metricsDue);
                    if (subs.get(networkId) != stamp) {
                        sendSnapshot(server, player, network);
                        // Only once sent: one that failed is tried again on the next pass.
                        subs.put(networkId, stamp);
                    }
                    if (metricsDue && NetworkTicker.isRunning(server, network)) {
                        send(player, metrics(server, network));
                    }
                } catch (RuntimeException | LinkageError e) {
                    long now = System.currentTimeMillis();
                    if (now - lastPushFailureLogged > 60_000) {
                        lastPushFailureLogged = now;
                        io.github.leadassimilator.reticulum.Reticulum.LOGGER.error("Couldn't send network {} ({}) to the editor (not logged again for a minute)", network.name(), network.id(), e);
                    }
                }
            }
        }
    }

    /**
     * A chunk loaded or unloaded: an open network with a device in it shows that device's state anew (loaded, what it
     * holds), though nothing in the network changed.
     */
    public static void onChunkChanged(net.neoforged.neoforge.event.level.ChunkEvent event) {
        if (SUBSCRIPTIONS.isEmpty() || event.getLevel().isClientSide() || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.world.level.ChunkPos chunk = event.getChunk().getPos();
        java.util.Set<UUID> open = new java.util.HashSet<>();
        SUBSCRIPTIONS.values().forEach(subs -> open.addAll(subs.keySet()));
        for (UUID id : open) {
            Network network = NetworkManager.network(level.getServer(), id);
            if (network != null && network.devices().stream().anyMatch(d -> d.pos().dimension() == level.dimension()
                    && chunk.equals(new net.minecraft.world.level.ChunkPos(d.pos().pos())))) {
                network.touchShown();
            }
        }
    }

    /** When a failed push was last logged. */
    private static long lastPushFailureLogged;

    /** The overflow revision each network's editors were last shown (taken once a second). */
    private static final Map<UUID, Integer> SHOWN = new HashMap<>();

    /**
     * What a snapshot is current for: the network's revision, whether it runs (a controller's chunk unloading changes
     * no revision, but the editor must stop showing it as running), and its overflow buffer as of the last second
     * (taken when {@code everySecond}, so a draining buffer sends a snapshot once a second, not every few ticks).
     */
    private static int stamp(MinecraftServer server, Network network, boolean everySecond) {
        if (everySecond) {
            SHOWN.put(network.id(), network.shownRevision());
        }
        int overflow = SHOWN.getOrDefault(network.id(), 0);
        // Spread apart so no change in one can cancel out a change in another (as a hash of the three could).
        return network.revision() * 1_000_003 + overflow * 2 + (NetworkTicker.isRunning(server, network) ? 1 : 0);
    }

    /** {@link #stamp(MinecraftServer, Network, boolean)} for one player: what they may do too (op/deop, a role changed). */
    private static int stamp(MinecraftServer server, Network network, ServerPlayer player, boolean everySecond) {
        int may = (Security.could(network, player, Role.EDITOR) ? 1 : 0) | (Security.could(network, player, Role.ADMIN) ? 2 : 0);
        return stamp(server, network, everySecond) * 4 + may;
    }

    /** Sends a fresh snapshot of the network to every subscriber now. */
    private static void pushSnapshots(MinecraftServer server, Network network) {
        for (Map.Entry<UUID, Map<UUID, Integer>> entry : SUBSCRIPTIONS.entrySet()) {
            Map<UUID, Integer> subs = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !subs.containsKey(network.id()) || !Security.canView(network, player)) {
                continue;
            }
            subs.put(network.id(), stamp(server, network, player, false));
            sendSnapshot(server, player, network);
        }
    }

    /** The network as this player may see it, pushed to their editor. */
    private static void sendSnapshot(MinecraftServer server, ServerPlayer player, Network network) {
        JsonObject push = new JsonObject();
        push.addProperty("type", "snapshot");
        push.add("network", Snapshots.full(server, network, player));
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
        CompiledSheet compiled = runtime.compiledIfPresent();
        if (compiled != null) {
            pins.putAll(compiled.pinKeys());
            pins.putAll(compiled.outputNames());
        }
        runtime.signalValues().forEach((id, value) -> signals.addProperty(pins.getOrDefault(id, id.toString()), value));
        out.add("signals", signals);
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
        ALLOWANCE.remove(event.getEntity().getUUID());
        LIST_LISTENERS.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SUBSCRIPTIONS.clear();
        SHOWN.clear();
        ALLOWANCE.clear();
        LIST_LISTENERS.clear();
    }

    /** Used by the "Open editor" button on the controller; the client turns this into a tab switch. */
    public static Optional<Network> networkAt(MinecraftServer server, UUID id) {
        return Optional.ofNullable(NetworkManager.network(server, id));
    }
}
