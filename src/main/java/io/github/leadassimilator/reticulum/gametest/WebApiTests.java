package io.github.leadassimilator.reticulum.gametest;

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

    private static void place(GameTestHelper helper, BlockPos rel, Block block, Direction against) {
        helper.setBlock(rel, block);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(rel), against, null);
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
