package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.place;

import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.protocol.web.WebApi;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Drives the web protocol the way the browser does, with JSON requests. */
@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class WebApiTests {
    private WebApiTests() {
    }

    private static JsonObject call(GameTestHelper helper, ServerPlayer player, String json) {
        try {
            return WebApi.handle(player, JsonParser.parseString(json).getAsJsonObject(), "t");
        } catch (WebApi.RequestException e) {
            helper.fail("Request failed: " + e.getMessage() + " for " + json);
            return null;
        }
    }

    private static String pos(GameTestHelper helper, BlockPos rel) {
        BlockPos p = helper.absolutePos(rel);
        return "{\"dimension\":\"minecraft:overworld\",\"pos\":[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]}";
    }

    @GameTest(template = "empty")
    public static void editFromJsonMovesItems(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.BARREL);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.BARREL);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        place(helper, new BlockPos(4, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        ((Container) helper.getBlockEntity(new BlockPos(3, 1, 2))).setItem(0, new ItemStack(Items.IRON_INGOT, 20));

        // A fake player: logging a mock player in would fire other mods' login handlers against a fake connection.
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "web-test"));
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        network.setOwner(UUID.randomUUID(), "someone else");
        network.setAccessMode(AccessMode.PRIVATE);
        String id = network.id().toString();

        // Not a member of a private network: can't even see it.
        try {
            WebApi.handle(player, JsonParser.parseString("{\"op\":\"subscribe\",\"network\":\"" + id + "\"}").getAsJsonObject(), "t");
            helper.fail("Non-member could subscribe to a private network");
        } catch (WebApi.RequestException expected) {
            // expected
        }
        network.members().put(player.getUUID(), new Member(player.getUUID(), "test", Role.EDITOR));

        JsonObject snapshot = call(helper, player, "{\"op\":\"subscribe\",\"network\":\"" + id + "\"}").getAsJsonObject("network");
        if (snapshot.getAsJsonArray("devices").size() != 2) {
            helper.fail("Snapshot should list 2 devices");
        }

        JsonObject created = call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"web\"}]}");
        String sheet = created.getAsJsonArray("results").get(0).getAsString();
        String source = UUID.randomUUID().toString();
        String sink = UUID.randomUUID().toString();
        JsonArray edits = JsonParser.parseString("[" +
                "{\"type\":\"updateSheet\",\"sheet\":\"" + sheet + "\",\"interval\":1}," +
                "{\"type\":\"addNode\",\"sheet\":\"" + sheet + "\",\"node\":{\"id\":\"" + source + "\",\"settings\":{\"kind\":\"device\",\"device\":" + pos(helper, new BlockPos(3, 1, 2)) + ",\"outputs\":[{\"resource\":\"item\"}]}}}," +
                "{\"type\":\"addNode\",\"sheet\":\"" + sheet + "\",\"node\":{\"id\":\"" + sink + "\",\"settings\":{\"kind\":\"device\",\"device\":" + pos(helper, new BlockPos(4, 1, 2)) + ",\"inputs\":[{\"resource\":\"item\"}]}}}," +
                "{\"type\":\"connect\",\"sheet\":\"" + sheet + "\",\"wire\":{\"id\":\"" + UUID.randomUUID() + "\",\"from\":\"" + source + "\",\"to\":\"" + sink + "\"}}," +
                "{\"type\":\"renameDevice\",\"device\":" + pos(helper, new BlockPos(4, 1, 2)) + ",\"name\":\"Output barrel\"}" +
                "]").getAsJsonArray();
        JsonObject request = new JsonObject();
        request.addProperty("op", "edit");
        request.addProperty("network", id);
        request.add("edits", edits);
        call(helper, player, request.toString());

        // A port pointing outside the network is refused.
        try {
            WebApi.handle(player, JsonParser.parseString("{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"addNode\",\"sheet\":\""
                    + sheet + "\",\"node\":{\"id\":\"" + UUID.randomUUID() + "\",\"settings\":{\"kind\":\"device\",\"device\":"
                    + pos(helper, new BlockPos(6, 1, 6)) + "}}}]}").getAsJsonObject(), "t");
            helper.fail("A sink outside the network was accepted");
        } catch (WebApi.RequestException expected) {
            // expected
        }

        helper.succeedWhen(() -> {
            Container out = (Container) helper.getBlockEntity(new BlockPos(4, 1, 2));
            if (out.countItem(Items.IRON_INGOT) != 20) {
                helper.fail("Items not moved by the web-built flow yet");
            }
            JsonObject again = call(helper, player, "{\"op\":\"subscribe\",\"network\":\"" + id + "\"}").getAsJsonObject("network");
            boolean named = again.getAsJsonArray("devices").asList().stream()
                    .anyMatch(d -> d.getAsJsonObject().has("name") && d.getAsJsonObject().get("name").getAsString().equals("Output barrel"));
            if (!named) {
                helper.fail("Rename not in snapshot");
            }
        });
    }

    /** A batch of edits is all or nothing: one refused undoes the ones before it (a new sheet, a template, a node). */
    @GameTest(template = "empty")
    public static void aRefusedEditUndoesItsBatch(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "web-test"));
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        network.setOwner(player.getUUID(), "web-test");
        String id = network.id().toString();
        String sheet = call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"t\"}]}")
                .getAsJsonArray("results").get(0).getAsString();
        int sheets = network.sheets().size();
        int nodes = network.sheet(UUID.fromString(sheet)).nodes().size();
        try {
            WebApi.handle(player, JsonParser.parseString("{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":["
                    + "{\"type\":\"createSheet\",\"name\":\"more\"},"
                    + "{\"type\":\"setFilter\",\"name\":\"ores\",\"expression\":\"minecraft:iron_ingot\"},"
                    + "{\"type\":\"addNode\",\"sheet\":\"" + sheet + "\",\"node\":{\"id\":\"" + UUID.randomUUID() + "\",\"settings\":{\"kind\":\"filter\",\"expression\":\"@ores\"}}},"
                    + "{\"type\":\"noSuchEdit\"}"
                    + "]}").getAsJsonObject(), "t");
            helper.fail("A batch with a refused edit was accepted");
        } catch (WebApi.RequestException expected) {
            // expected
        }
        if (network.sheets().size() != sheets || network.filters().containsKey("ores") || network.sheet(UUID.fromString(sheet)).nodes().size() != nodes) {
            helper.fail("The edits before the refused one were kept: " + network.sheets().size() + " sheets, templates " + network.filters().keySet());
        }
        helper.succeed();
    }

    /** Removing a sheet pin removes the wires on it one level up, on the sheet's node: none is left on a gone handle. */
    @GameTest(template = "empty")
    public static void removingAPinRemovesItsWiresOutside(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "web-test"));
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        network.setOwner(player.getUUID(), "web-test");
        String id = network.id().toString();
        String inner = call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"inner\"}]}")
                .getAsJsonArray("results").get(0).getAsString();
        Network.Placement placed = network.placement(UUID.fromString(inner));
        if (placed == null) {
            helper.fail("The new sheet has no node");
            return;
        }
        String outer = placed.parent().id().toString();
        String holder = placed.node().id().toString();
        String pin = UUID.randomUUID().toString();
        String filter = UUID.randomUUID().toString();
        call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":["
                + "{\"type\":\"addNode\",\"sheet\":\"" + inner + "\",\"node\":{\"id\":\"" + pin + "\",\"settings\":{\"kind\":\"sheet_input\",\"name\":\"in\",\"slot\":0}}},"
                + "{\"type\":\"addNode\",\"sheet\":\"" + outer + "\",\"node\":{\"id\":\"" + filter + "\",\"settings\":{\"kind\":\"filter\",\"expression\":\"\"}}},"
                + "{\"type\":\"connect\",\"sheet\":\"" + outer + "\",\"wire\":{\"id\":\"" + UUID.randomUUID() + "\",\"from\":\"" + filter + "\",\"to\":\"" + holder + "\",\"input\":0}}"
                + "]}");
        call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"removeNode\",\"sheet\":\"" + inner + "\",\"node\":\"" + pin + "\"}]}");
        if (placed.parent().wires().stream().anyMatch(w -> w.to().toString().equals(holder))) {
            helper.fail("A wire is left on the removed pin's handle");
        }
        helper.succeed();
    }

    /** Sheets nest at most Limits.SHEET_DEPTH deep: one more is refused rather than taken and later moved to the root. */
    @GameTest(template = "empty")
    public static void sheetsNestOnlySoDeep(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "web-test"));
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        network.setOwner(player.getUUID(), "web-test");
        String id = network.id().toString();
        String parent = network.root().id().toString();
        for (int i = 0; i < io.github.leadassimilator.reticulum.network.Limits.SHEET_DEPTH; i++) {
            parent = call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"s" + i
                    + "\",\"parent\":\"" + parent + "\"}]}").getAsJsonArray("results").get(0).getAsString();
        }
        try {
            WebApi.handle(player, JsonParser.parseString("{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"deep\",\"parent\":\""
                    + parent + "\"}]}").getAsJsonObject(), "t");
            helper.fail("A sheet past the depth limit was accepted");
        } catch (WebApi.RequestException expected) {
            // expected
        }
        if (network.depth(network.sheet(UUID.fromString(parent))) != io.github.leadassimilator.reticulum.network.Limits.SHEET_DEPTH) {
            helper.fail("The deepest sheet isn't where it was put: depth " + network.depth(network.sheet(UUID.fromString(parent))));
        }
        helper.succeed();
    }

    /** Renaming a filter template renames it where it is used: other templates and the filters nodes hold. */
    @GameTest(template = "empty")
    public static void renamingATemplateRenamesItsUses(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.BARREL);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "web-test"));
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        network.members().put(player.getUUID(), new Member(player.getUUID(), "test", Role.EDITOR));
        String id = network.id().toString();
        String sheet = call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"createSheet\",\"name\":\"t\"}]}")
                .getAsJsonArray("results").get(0).getAsString();
        String filterNode = UUID.randomUUID().toString();
        String deviceNode = UUID.randomUUID().toString();
        call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":["
                + "{\"type\":\"setFilter\",\"name\":\"ores\",\"expression\":\"minecraft:iron_ingot\"},"
                + "{\"type\":\"setFilter\",\"name\":\"metals\",\"expression\":\"@ores | minecraft:gold_ingot\"},"
                + "{\"type\":\"addNode\",\"sheet\":\"" + sheet + "\",\"node\":{\"id\":\"" + filterNode + "\",\"settings\":{\"kind\":\"filter\",\"expression\":\"@ores\"}}},"
                + "{\"type\":\"addNode\",\"sheet\":\"" + sheet + "\",\"node\":{\"id\":\"" + deviceNode + "\",\"settings\":{\"kind\":\"device\",\"device\":"
                + pos(helper, new BlockPos(3, 1, 2)) + ",\"outputs\":[{\"resource\":\"item\",\"keep\":[{\"scope\":\"per_type\",\"amount\":5,\"filter\":\"@ores\"}]}]}}}"
                + "]}");
        call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"renameFilter\",\"from\":\"ores\",\"to\":\"iron\"}]}");
        expectTemplates(helper, network, sheet, filterNode, deviceNode, "iron");
        // The reverse rename (undo) puts everything back.
        call(helper, player, "{\"op\":\"edit\",\"network\":\"" + id + "\",\"edits\":[{\"type\":\"renameFilter\",\"from\":\"iron\",\"to\":\"ores\"}]}");
        expectTemplates(helper, network, sheet, filterNode, deviceNode, "ores");
        helper.succeed();
    }

    private static void expectTemplates(GameTestHelper helper, Network network, String sheetId, String filterNode, String deviceNode, String name) {
        if (!network.filters().containsKey(name) || network.filters().size() != 2) {
            helper.fail("Templates after the rename: " + network.filters().keySet());
        }
        if (!("@" + name + " | minecraft:gold_ingot").equals(network.filters().get("metals"))) {
            helper.fail("The template using it wasn't renamed: " + network.filters().get("metals"));
        }
        var sheet = network.sheet(UUID.fromString(sheetId));
        if (!(sheet.node(UUID.fromString(filterNode)).settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.FilterSettings f)
                || !f.expression().equals("@" + name)) {
            helper.fail("The filter node wasn't renamed: " + sheet.node(UUID.fromString(filterNode)).settings());
        }
        if (!(sheet.node(UUID.fromString(deviceNode)).settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings d)
                || !d.outputs().get(0).keep().get(0).filter().equals("@" + name)) {
            helper.fail("The keep rule wasn't renamed: " + sheet.node(UUID.fromString(deviceNode)).settings());
        }
    }

    /** The web server needs Netty's HTTP module, which Minecraft doesn't ship; it must load from mod code. */
    @GameTest(template = "empty")
    public static void nettyHttpIsAvailable(GameTestHelper helper) {
        try {
            Class.forName("io.netty.handler.codec.http.HttpServerCodec", false, WebApiTests.class.getClassLoader());
            Class.forName("io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory", false, WebApiTests.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            helper.fail("netty-codec-http is not visible to the mod: " + e.getMessage());
        }
        helper.succeed();
    }
}
