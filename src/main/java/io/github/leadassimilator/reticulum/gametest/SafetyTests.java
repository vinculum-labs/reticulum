package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.place;
import static io.github.leadassimilator.reticulum.gametest.TestSupport.check;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonParser;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Identifies;
import io.github.leadassimilator.reticulum.network.Identify;
import io.github.leadassimilator.reticulum.network.Limits;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.web.WebApi;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import io.github.leadassimilator.reticulum.security.Role;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** What one player can't do to the server or to other players' networks: crash, stall, bloat, or take over. */
@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class SafetyTests {
    private SafetyTests() {
    }

    private static Network network(GameTestHelper helper, BlockPos rel) {
        return Topology.network(helper.getLevel(), helper.absolutePos(rel));
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), name));
    }

    private static String pos(GameTestHelper helper, BlockPos rel) {
        BlockPos p = helper.absolutePos(rel);
        return "{\"dimension\":\"minecraft:overworld\",\"pos\":[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]}";
    }

    /** Sends one edit; returns the error, or null when it was accepted. */
    @Nullable
    private static String edit(ServerPlayer player, Network network, String edit) {
        try {
            WebApi.handle(player, JsonParser.parseString("{\"op\":\"edit\",\"network\":\"" + network.id() + "\",\"edits\":[" + edit + "]}")
                    .getAsJsonObject(), "t");
            return null;
        } catch (WebApi.RequestException e) {
            return e.getMessage();
        }
    }

    /** A controller, a cable and a connector with a barrel; the player owns it. */
    private static Network rig(GameTestHelper helper, ServerPlayer owner) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.BARREL);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        Network network = network(helper, new BlockPos(1, 1, 1));
        network.setOwner(owner.getUUID(), owner.getGameProfile().getName());
        return network;
    }

    @GameTest(template = "empty")
    public static void longNamesAreCutAndMarkersStaySendable(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        String barrel = pos(helper, new BlockPos(3, 1, 2));
        String longName = "x".repeat(300);
        check(helper, edit(owner, network, "{\"type\":\"renameDevice\",\"device\":" + barrel + ",\"name\":\"" + longName + "\"}") == null, "rename refused");
        check(helper, edit(owner, network, "{\"type\":\"renameFace\",\"device\":" + barrel + ",\"face\":\"up\",\"name\":\"" + longName + "\"}") == null,
                "face rename refused");
        GlobalPos device = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)));
        check(helper, network.device(device).name().length() == Limits.NAME, "device name not cut: " + network.device(device).name().length());
        check(helper, edit(owner, network, "{\"type\":\"identify\",\"kind\":\"face\",\"device\":" + barrel + ",\"face\":\"up\",\"on\":true}") == null,
                "identify refused");
        // The packet that tells players what is lit must still encode: it used to fail (and kick) on long labels.
        var markers = new Payloads.IdentifyMarkers(Identifies.markersFor(owner));
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        Payloads.IdentifyMarkers.STREAM_CODEC.encode(buffer, markers);
        // Other tests run alongside, so only this device's marker is looked at.
        check(helper, markers.markers().stream().anyMatch(m -> m.pos().equals(device) && m.label().length() <= 256), "no marker for the device");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyThingsOnTheNetworkCanBeIdentified(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        String error = edit(owner, network, "{\"type\":\"identify\",\"kind\":\"device\",\"device\":" + pos(helper, new BlockPos(6, 1, 6)) + ",\"on\":true}");
        check(helper, error != null, "a block outside the network was identified");
        check(helper, network.identified().isEmpty(), "the refused identify was kept");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deeplyNestedFiltersAreRefused(GameTestHelper helper) {
        for (String filter : List.of("(".repeat(20_000) + "*" + ")".repeat(20_000), "!".repeat(20_000) + "*")) {
            try {
                Filters.items(filter, Map.of());
                helper.fail("a filter nested 20000 deep was accepted");
            } catch (FilterException expected) {
                // refused before the parser could overflow the stack
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void runawayTemplatesAreRefused(GameTestHelper helper) {
        // Each template uses the one before it 8 times: 8^16 terms once filled in.
        Map<String, String> templates = new HashMap<>();
        templates.put("t0", "minecraft:stone");
        for (int i = 1; i <= 16; i++) {
            templates.put("t" + i, ("@t" + (i - 1) + " ").repeat(8).strip().replace(" ", " | "));
        }
        try {
            Filters.items("@t16", templates);
            helper.fail("a filter expanding to 8^16 terms was accepted");
        } catch (FilterException expected) {
            // refused
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void errorsNameTheNodeTheEditorShows(GameTestHelper helper) {
        Network network = NetworkManager.create(helper.getLevel().getServer(), null);
        // A blueprint with a broken node inside: the error points at the instance node placed on the main sheet.
        Sheet inner = new Sheet(UUID.randomUUID(), "inner");
        inner.setBlueprint(true);
        inner.add(Node.of(new NodeSettings.InstanceSettings(UUID.randomUUID())));
        network.sheets().add(inner);
        Sheet main = new Sheet(UUID.randomUUID(), "main");
        Node missing = Node.of(new NodeSettings.InstanceSettings(UUID.randomUUID()));
        Node placed = Node.of(new NodeSettings.InstanceSettings(inner.id()));
        main.add(missing);
        main.add(placed);
        network.sheets().add(main);
        CompiledSheet compiled = CompiledSheet.compile(main, network);
        NetworkManager.delete(helper.getLevel().getServer(), network);
        Set<UUID> shown = new HashSet<>();
        compiled.errors().forEach(e -> shown.add(e.node() == null ? null : compiled.owner(e.node())));
        check(helper, shown.equals(Set.of(missing.id(), placed.id())), "errors point at " + shown + ": " + compiled.errors());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blueprintInstancesThatExpandTooFarAreRefused(GameTestHelper helper) {
        Network network = NetworkManager.create(helper.getLevel().getServer(), null);
        // A leaf blueprint of 10 nodes, then three levels each using the one below 10 times: 10^4 instances of the leaf.
        Sheet below = new Sheet(UUID.randomUUID(), "leaf");
        below.setBlueprint(true);
        for (int i = 0; i < 10; i++) {
            below.add(Node.of(new NodeSettings.RateLimitSettings(20)));
        }
        network.sheets().add(below);
        for (int level = 1; level <= 3; level++) {
            Sheet sheet = new Sheet(UUID.randomUUID(), "level " + level);
            sheet.setBlueprint(true);
            for (int i = 0; i < 10; i++) {
                sheet.add(Node.of(new NodeSettings.InstanceSettings(below.id())));
            }
            network.sheets().add(sheet);
            below = sheet;
        }
        Sheet main = new Sheet(UUID.randomUUID(), "main");
        for (int i = 0; i < 10; i++) {
            main.add(Node.of(new NodeSettings.InstanceSettings(below.id())));
        }
        network.sheets().add(main);
        long start = System.nanoTime();
        CompiledSheet compiled = CompiledSheet.compile(main, network);
        long millis = (System.nanoTime() - start) / 1_000_000;
        NetworkManager.delete(helper.getLevel().getServer(), network);
        check(helper, compiled.errors().stream().anyMatch(e -> e.message().contains("too big")), "no error for a sheet that expands to 10^5 nodes: " + compiled.errors());
        check(helper, millis < 2000, "refusing it took " + millis + " ms");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCutOffPieceOfALiveNetworkIsNotMergedAway(GameTestHelper helper) {
        // Network A: a controller and two cables; the second cable is then disconnected from the first.
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        Network a = network(helper, new BlockPos(1, 1, 1));
        Topology.toggle(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)), Direction.EAST, null);
        // Network B, whose cable ends next to A's cut-off piece.
        place(helper, new BlockPos(5, 1, 3), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(4, 1, 3), ModBlocks.CABLE.get(), Direction.EAST);
        place(helper, new BlockPos(3, 1, 3), ModBlocks.CABLE.get(), Direction.EAST);
        place(helper, new BlockPos(3, 1, 2), ModBlocks.CABLE.get(), Direction.SOUTH);
        Network b = network(helper, new BlockPos(5, 1, 3));
        check(helper, a != b, "setup: two networks expected");

        // Joining A's piece to B must not merge all of A (whose controller is still there) into B: only the piece moves.
        Topology.toggle(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)), Direction.NORTH, null);
        check(helper, NetworkManager.network(helper.getLevel().getServer(), a.id()) == a, "network A was merged away");
        check(helper, network(helper, new BlockPos(1, 1, 1)) == a, "A's controller changed network");
        check(helper, network(helper, new BlockPos(2, 1, 1)) == a, "A's own cable changed network");
        check(helper, network(helper, new BlockPos(3, 1, 1)) == b, "the cut-off piece didn't move to B");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mergingCapsTheJoiningMembersRoles(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Network into = NetworkManager.create(server, null);
        Network from = NetworkManager.create(server, null);
        UUID owner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        from.setOwner(owner, "from-owner");
        from.members().put(admin, new Member(admin, "from-admin", Role.ADMIN));
        from.members().put(viewer, new Member(viewer, "from-viewer", Role.VIEWER));
        NetworkManager.absorb(server, into, from, Role.EDITOR);
        check(helper, into.members().get(owner).role() == Role.EDITOR, "absorbed owner joined as " + into.members().get(owner).role());
        check(helper, into.members().get(admin).role() == Role.EDITOR, "absorbed admin joined as " + into.members().get(admin).role());
        check(helper, into.members().get(viewer).role() == Role.VIEWER, "a viewer was raised to " + into.members().get(viewer).role());
        NetworkManager.delete(server, into);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWirelessConnectorCantBeTakenFromItsNetwork(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer stranger = player(helper, "stranger");
        Network victims = NetworkManager.create(server, null);
        victims.setOwner(UUID.randomUUID(), "victim");
        victims.setAccessMode(AccessMode.PRIVATE);
        Network theirs = NetworkManager.create(server, null);
        theirs.setOwner(stranger.getUUID(), "stranger");
        BlockPos connector = new BlockPos(2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.BARREL);
        place(helper, connector, ModBlocks.CONNECTOR.get(), null);
        Topology.bindWireless(helper.getLevel(), helper.absolutePos(connector), victims, null);
        check(helper, network(helper, connector) == victims, "setup: connector not on the victim's network");

        Topology.bindWireless(helper.getLevel(), helper.absolutePos(connector), theirs, stranger);
        check(helper, network(helper, connector) == victims, "a player without access moved the connector to their own network");
        NetworkManager.delete(server, victims);
        NetworkManager.delete(server, theirs);
        helper.succeed();
    }

    /** The editor's sheet edits keep the tree whole: made inside a parent, never moved into themselves, deleted whole. */
    @GameTest(template = "empty")
    public static void sheetEditsKeepTheTree(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        Sheet root = network.root();
        check(helper, edit(owner, network, "{\"type\":\"createSheet\",\"name\":\"smelting\"}") == null, "creating a sheet failed");
        Sheet smelting = network.sheetNamed("smelting");
        check(helper, smelting != null && network.placement(smelting.id()).parent() == root, "a new sheet didn't go into the root");
        check(helper, edit(owner, network, "{\"type\":\"createSheet\",\"name\":\"ores\",\"parent\":\"" + smelting.id() + "\"}") == null,
                "creating a sheet inside another failed");
        Sheet ores = network.sheetNamed("ores");
        check(helper, ores != null && network.placement(ores.id()).parent() == smelting, "the sheet didn't go inside its parent");
        check(helper, edit(owner, network, "{\"type\":\"moveSheet\",\"sheet\":\"" + smelting.id() + "\",\"parent\":\"" + ores.id() + "\"}") != null,
                "a sheet moved inside one of its own sheets");
        check(helper, edit(owner, network, "{\"type\":\"deleteSheet\",\"sheet\":\"" + root.id() + "\"}") != null, "the root was deleted");
        UUID smeltingNode = network.placement(smelting.id()).node().id();
        check(helper, edit(owner, network, "{\"type\":\"removeNode\",\"sheet\":\"" + root.id() + "\",\"node\":\"" + smeltingNode + "\"}") == null,
                "deleting a sheet node failed");
        check(helper, network.sheet(smelting.id()) == null && network.sheet(ores.id()) == null, "a deleted sheet node left its sheets behind");
        check(helper, root.node(smeltingNode) == null, "the sheet node stayed");
        helper.succeed();
    }

    /** A device lives on one sheet: a second device node for it, on any sheet of the tree, is refused. */
    @GameTest(template = "empty")
    public static void aDeviceHasOneNode(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        Sheet root = network.root();
        var barrel = network.devices().iterator().next().pos();
        String device = net.minecraft.core.GlobalPos.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, barrel).getOrThrow().toString();
        String node = "{\"type\":\"addNode\",\"sheet\":\"%s\",\"node\":{\"id\":\"%s\",\"settings\":{\"kind\":\"device\",\"device\":" + device + "}}}";
        check(helper, edit(owner, network, node.formatted(root.id(), UUID.randomUUID())) == null, "the first node for the barrel was refused");
        check(helper, edit(owner, network, "{\"type\":\"createSheet\",\"name\":\"other\"}") == null, "creating a sheet failed");
        Sheet other = network.sheetNamed("other");
        String refused = edit(owner, network, node.formatted(other.id(), UUID.randomUUID()));
        check(helper, refused != null && refused.contains("lives in"), "a second node for the barrel was allowed: " + refused);
        helper.succeed();
    }

    /** Make blueprint: the sheet's devices become slots, and the sheet's node becomes an instance filled with its blocks. */
    @GameTest(template = "empty")
    public static void makeBlueprintKeepsTheSheetAsItsFirstInstance(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        var barrel = network.devices().iterator().next().pos();
        check(helper, edit(owner, network, "{\"type\":\"createSheet\",\"name\":\"work\"}") == null, "creating a sheet failed");
        Sheet work = network.sheetNamed("work");
        Node device = work.add(Node.of(new NodeSettings.DeviceSettings(barrel, List.of(), List.of())));
        UUID holder = network.placement(work.id()).node().id();
        check(helper, edit(owner, network, "{\"type\":\"makeBlueprint\",\"sheet\":\"" + work.id() + "\"}") == null, "make blueprint failed");
        Sheet blueprint = network.sheets().stream().filter(Sheet::blueprint).findFirst().orElse(null);
        check(helper, blueprint != null && network.sheet(work.id()) == null, "no blueprint, or the sheet is still there");
        Node slot = blueprint.nodes().getFirst();
        check(helper, slot.settings() instanceof NodeSettings.DeviceSettings d && d.block().isPresent() && !slot.id().equals(device.id()),
                "the slot has no block type, or kept the old id");
        Node instance = network.root().node(holder);
        check(helper, instance != null && instance.settings() instanceof NodeSettings.InstanceSettings c && c.sheet().equals(blueprint.id())
                && barrel.equals(c.bindings().get(slot.id()).device()), "the sheet's node isn't an instance filled with its block");
        // The barrel lives in the instance now: a device node for it elsewhere is refused.
        String node = "{\"type\":\"addNode\",\"sheet\":\"%s\",\"node\":{\"id\":\"%s\",\"settings\":{\"kind\":\"device\",\"device\":"
                + GlobalPos.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, barrel).getOrThrow() + "}}}";
        String refused = edit(owner, network, node.formatted(network.root().id(), UUID.randomUUID()));
        check(helper, refused != null && refused.contains("lives in"), "a device filling a slot got a second node: " + refused);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sheetCountIsLimited(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        // The root counts as one of the sheets.
        network.ensureTree();
        while (network.sheets().size() < Limits.SHEETS) {
            network.sheets().add(new Sheet(UUID.randomUUID(), "s"));
        }
        check(helper, edit(owner, network, "{\"type\":\"createSheet\",\"name\":\"one too many\"}") != null, "a sheet past the limit was created");
        check(helper, network.sheets().size() == Limits.SHEETS, "the refused sheet was kept");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCopiedConnectorGetsIdsOfItsOwn(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        var original = (io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity) helper.getBlockEntity(new BlockPos(3, 1, 1));
        var data = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        // As ctrl+pick-block or /clone would: the same data, somewhere else.
        BlockPos copyAt = new BlockPos(6, 1, 6);
        helper.setBlock(copyAt, ModBlocks.CONNECTOR.get());
        var copy = (io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity) helper.getBlockEntity(copyAt);
        copy.loadWithComponents(data, helper.getLevel().registryAccess());
        check(helper, !copy.isRegistered(), "the copy counts as already registered, so it never joins anything");
        check(helper, !copy.connectorId().equals(original.connectorId()), "the copy shares the original's connector id");
        check(helper, Topology.network(helper.getLevel(), helper.absolutePos(copyAt)) == null, "the copy claims the original's network");
        check(helper, network.connector(original.connectorId()).pos().pos().equals(helper.absolutePos(new BlockPos(3, 1, 1))),
                "the original's record moved");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDeviceFlowsUseIsKeptWhenItsBlockGoes(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        GlobalPos barrel = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)));
        Sheet sheet = new Sheet(UUID.randomUUID(), "uses the barrel");
        sheet.add(Node.of(new NodeSettings.DeviceSettings(barrel, List.of(), List.of())));
        network.sheets().add(sheet);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.AIR);
        helper.succeedWhen(() -> {
            var record = network.device(barrel);
            check(helper, record != null, "the unnamed barrel's record was dropped although a sheet uses it");
            check(helper, record.state() != io.github.leadassimilator.reticulum.network.DeviceRecord.State.ACTIVE, "the gone barrel is still active");
        });
    }

    @GameTest(template = "empty")
    public static void screenViewsSurviveTheTrip(GameTestHelper helper) {
        // The views' codecs are written field by field (more fields than a composite takes): every field must come back.
        var access = helper.getLevel().registryAccess();
        var side = new io.github.leadassimilator.reticulum.protocol.Views.SideView(Direction.EAST, io.github.leadassimilator.reticulum.protocol.Views.SideKind.DEVICE,
                io.github.leadassimilator.reticulum.protocol.Views.JointView.CONNECTED, true, net.minecraft.world.item.ItemStack.EMPTY, "Barrel", "Logs",
                List.of("", "In", "", "", "", "", "Out"), 0b1000_0010);
        var block = new io.github.leadassimilator.reticulum.protocol.Views.BlockView(new BlockPos(1, 2, 3), true, "Base", "Connector (1, 2, 3)", "Main",
                List.of(side), true, false, true);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), access);
        io.github.leadassimilator.reticulum.protocol.Views.BlockView.STREAM_CODEC.encode(buffer, block);
        check(helper, io.github.leadassimilator.reticulum.protocol.Views.BlockView.STREAM_CODEC.decode(buffer).equals(block), "the block view changed on the way");
        var sheet = new io.github.leadassimilator.reticulum.protocol.Views.SheetEntry(UUID.randomUUID(), "Smelting", 1, true, false, true, 2);
        var controller = new io.github.leadassimilator.reticulum.protocol.Views.ControllerView(new BlockPos(4, 5, 6), UUID.randomUUID(), "Base", "owner",
                AccessMode.PASSWORD, true, 3, 7, 1, true, false, 2, true, false, io.github.leadassimilator.reticulum.network.Switches.Redstone.HIGH,
                List.of(sheet), List.of("4x iron"), "100 FE");
        buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), access);
        io.github.leadassimilator.reticulum.protocol.Views.ControllerView.STREAM_CODEC.encode(buffer, controller);
        check(helper, io.github.leadassimilator.reticulum.protocol.Views.ControllerView.STREAM_CODEC.decode(buffer).equals(controller),
                "the controller view changed on the way");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void identifyColorChangeKeepsItOn(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "owner");
        Network network = rig(helper, owner);
        GlobalPos device = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)));
        Identifies.set(helper.getLevel().getServer(), network, Identify.device(device, 0), true);
        Identifies.set(helper.getLevel().getServer(), network, Identify.device(device, 0xFF0000), true);
        check(helper, network.identified().size() == 1 && network.identified().getFirst().color() == 0xFF0000, "recolor didn't keep one identify");
        helper.succeed();
    }
}
