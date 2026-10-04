package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.place;
import static io.github.leadassimilator.reticulum.gametest.TestSupport.check;

import java.util.UUID;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.DeviceRecord;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class TopologyTests {
    private TopologyTests() {
    }

    private static NetworkBlockEntity be(GameTestHelper helper, BlockPos rel) {
        NetworkBlockEntity be = Topology.blockEntity(helper.getLevel(), helper.absolutePos(rel));
        if (be == null) {
            helper.fail("No network block at " + rel);
        }
        return be;
    }

    @Nullable
    private static UUID net(GameTestHelper helper, BlockPos rel) {
        return Topology.networkOf(helper.getLevel(), be(helper, rel));
    }

    private static Network network(GameTestHelper helper, BlockPos rel) {
        Network network = NetworkManager.network(helper.getLevel().getServer(), net(helper, rel));
        if (network == null) {
            helper.fail("No network at " + rel);
        }
        return network;
    }

    /**
     * A connector bound to network B, placed against network A's controller, stays on B and can power A's controller:
     * one network switches another through the controller's own redstone setting.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void boundConnectorSwitchesAnotherNetworksController(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(5, 1, 5), ModBlocks.CONTROLLER.get(), null);
        Network a = network(helper, new BlockPos(1, 1, 1));
        Network b = network(helper, new BlockPos(5, 1, 5));
        check(helper, a != b, "the two controllers share a network");
        a.switches().setRedstone(io.github.leadassimilator.reticulum.network.Switches.Redstone.LOW);
        // B reaches its wireless connector with an antenna (without one, a connector no cable joins does nothing).
        ((io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity) helper.getBlockEntity(new BlockPos(5, 1, 5))).setAntennaTier(1);

        // Placed from a stack bound to B, against A's controller.
        BlockPos at = new BlockPos(2, 1, 1);
        helper.setBlock(at, ModBlocks.CONNECTOR.get());
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(ModBlocks.CONNECTOR.get());
        stack.set(io.github.leadassimilator.reticulum.registry.ModDataComponents.NETWORK_ID.get(), b.id());
        be(helper, at).applyComponentsFromItemStack(stack);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(at), Direction.WEST, null);

        check(helper, b.id().equals(net(helper, at)), "the bound connector joined the controller's network instead of its own");
        var controllerA = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        check(helper, b.connectors().stream().anyMatch(c -> c.devices().contains(controllerA)), "the connector doesn't reach A's controller as a device");

        // B always powers A's controller: A, set to stop while powered, stops.
        var timer = io.github.leadassimilator.reticulum.graph.Node.of(new io.github.leadassimilator.reticulum.graph.NodeSettings.TimerSettings(1));
        var lamp = io.github.leadassimilator.reticulum.graph.Node.of(new io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings(controllerA,
                java.util.List.of(new io.github.leadassimilator.reticulum.graph.NodeSettings.Input(io.github.leadassimilator.reticulum.graph.ResourceKind.REDSTONE,
                        java.util.List.of(), java.util.Optional.empty(), java.util.List.of())), java.util.List.of()));
        b.root().add(timer);
        b.root().add(lamp);
        b.root().connect(new io.github.leadassimilator.reticulum.graph.Wire(UUID.randomUUID(), timer.id(), 0, lamp.id(), 0, 0));
        helper.succeedWhen(() -> {
            check(helper, helper.getLevel().getBestNeighborSignal(helper.absolutePos(new BlockPos(1, 1, 1))) > 0, "A's controller isn't powered by B");
            check(helper, io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(a.id()).sheetsOff().contains(a.root().id()),
                    "A kept running while its controller was powered");
        });
    }

    /** A connector bound elsewhere, placed against a controller and then picked onto that controller's network, joins it. */
    @GameTest(template = "empty")
    public static void connectorRebindToTheControllerBesideItJoinsIt(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(5, 1, 5), ModBlocks.CONTROLLER.get(), null);
        Network a = network(helper, new BlockPos(1, 1, 1));
        Network b = network(helper, new BlockPos(5, 1, 5));
        BlockPos at = new BlockPos(2, 1, 1);
        helper.setBlock(at, ModBlocks.CONNECTOR.get());
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(ModBlocks.CONNECTOR.get());
        stack.set(io.github.leadassimilator.reticulum.registry.ModDataComponents.NETWORK_ID.get(), b.id());
        be(helper, at).applyComponentsFromItemStack(stack);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(at), Direction.WEST, null);
        check(helper, b.id().equals(net(helper, at)), "the bound connector didn't stay on its own network");

        Topology.rebind(helper.getLevel(), helper.absolutePos(at), a, null);
        check(helper, a.id().equals(net(helper, at)), "the connector didn't move to the controller's network");
        check(helper, Topology.connected(helper.getLevel(), helper.absolutePos(at), Direction.WEST),
                "joint is " + Topology.jointState(helper.getLevel(), helper.absolutePos(at), Direction.WEST));
        var controllerA = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        check(helper, a.connectors().stream().noneMatch(c -> c.devices().contains(controllerA)), "its own controller is one of its devices");
        check(helper, b.connectors().isEmpty(), "the old network kept the connector");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cablesAndConnectorJoinController(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        place(helper, new BlockPos(4, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);

        UUID id = net(helper, new BlockPos(1, 1, 1));
        check(helper, id != null, "controller has no network");
        for (int x = 2; x <= 4; x++) {
            check(helper, id.equals(net(helper, new BlockPos(x, 1, 1))), "block " + x + " not on the controller's network");
        }
        Network network = network(helper, new BlockPos(1, 1, 1));
        check(helper, network.connectors().size() == 1, "expected 1 connector, got " + network.connectors().size());
        check(helper, network.devices().size() == 1, "expected the chest as the only device, got " + network.devices().size());
        helper.succeed();
    }

    /** A block closing a loop touches its own network's section twice: both joints connect (neither is cut off). */
    @GameTest(template = "empty")
    public static void closingALoopConnectsBothJoints(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(2, 1, 2), ModBlocks.CABLE.get(), Direction.NORTH);
        // The square's last corner: next to the controller (north) and the cable before it (east).
        place(helper, new BlockPos(1, 1, 2), ModBlocks.CABLE.get(), Direction.EAST);
        BlockPos corner = helper.absolutePos(new BlockPos(1, 1, 2));
        check(helper, Topology.connected(helper.getLevel(), corner, Direction.NORTH), "the corner isn't joined to the controller");
        check(helper, Topology.connected(helper.getLevel(), corner, Direction.EAST), "the corner isn't joined to the cable it was placed against");
        helper.succeed();
    }

    /**
     * A piece cut off from a network whose controller is still there can be joined to another network: just that
     * piece moves, and the first network keeps its controller and the rest of its cable.
     */
    @GameTest(template = "empty")
    public static void aCutOffPieceJoinsAnotherNetwork(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.CHEST);
        place(helper, new BlockPos(4, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        UUID a = net(helper, new BlockPos(1, 1, 1));
        net.minecraft.core.GlobalPos chest = net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 1, 2)));
        network(helper, new BlockPos(1, 1, 1)).device(chest).setName("ore box");
        // Cut 3-4 off from A: it stays A's (A's controller is still there).
        Topology.toggle(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)), Direction.EAST, null);
        check(helper, a.equals(net(helper, new BlockPos(3, 1, 1))), "setup: the cut-off piece should still be A's");
        // A second network beside it; its cable isn't joined to the piece.
        place(helper, new BlockPos(6, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(5, 1, 1), ModBlocks.CABLE.get(), Direction.EAST);
        UUID b = net(helper, new BlockPos(6, 1, 1));
        check(helper, !a.equals(b), "setup: two networks expected");
        Topology.toggle(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 1)), Direction.WEST, null);
        check(helper, Topology.connected(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 1)), Direction.WEST), "the joint wasn't connected");
        check(helper, b.equals(net(helper, new BlockPos(3, 1, 1))) && b.equals(net(helper, new BlockPos(4, 1, 1))), "the piece didn't move to B");
        check(helper, a.equals(net(helper, new BlockPos(2, 1, 1))) && a.equals(net(helper, new BlockPos(1, 1, 1))), "A lost its controller or cable");
        var moved = network(helper, new BlockPos(6, 1, 1)).device(chest);
        check(helper, moved != null && "ore box".equals(moved.name()), "the chest's name didn't come along: " + (moved == null ? "no record" : moved.name()));
        check(helper, network(helper, new BlockPos(1, 1, 1)).device(chest) == null, "A kept an orphaned record of the chest whose name moved on");
        helper.succeed();
    }

    /** A controller placed and broken leaves no network behind, though every network has a top sheet. */
    @GameTest(template = "empty")
    public static void aControllerPlacedAndBrokenLeavesNoNetwork(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        Network network = network(helper, new BlockPos(1, 1, 1));
        network.ensureTree();
        check(helper, network.root() != null, "setup: the network should have its top sheet");
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        check(helper, NetworkManager.network(helper.getLevel().getServer(), network.id()) == null, "the empty network was left behind");
        helper.succeed();
    }

    /** Picking another network on a cable or connector: a cut-off piece moves whole, a wired connector is unwired and bound, a cable to a controller stays. */
    @GameTest(template = "empty")
    public static void pickingANetworkOnACableOrConnector(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        place(helper, new BlockPos(5, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(6, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(1, 1, 5), ModBlocks.CONTROLLER.get(), null);
        UUID a = net(helper, new BlockPos(1, 1, 1));
        Network b = network(helper, new BlockPos(1, 1, 5));
        // 5-6 is cut off from A's controller (nothing at 4): on A, or no network, either way it moves whole.
        Topology.rebind(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 1)), b, null);
        check(helper, b.id().equals(net(helper, new BlockPos(5, 1, 1))) && b.id().equals(net(helper, new BlockPos(6, 1, 1))), "the piece didn't move to B");
        // A cable whose cable leads to A's controller stays A's.
        Topology.rebind(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)), b, null);
        check(helper, a.equals(net(helper, new BlockPos(2, 1, 1))), "a cable on A's controller's cable moved");
        // A connector on it is unwired and bound on its own to B.
        Topology.rebind(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1)), b, null);
        check(helper, b.id().equals(net(helper, new BlockPos(3, 1, 1))), "the connector wasn't bound to B");
        check(helper, !Topology.isWired(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1))), "the connector is still wired");
        check(helper, a.equals(net(helper, new BlockPos(2, 1, 1))), "A's cable changed network");
        helper.succeed();
    }

    /**
     * A connector moved by a contraption (as Create does it: lifted with the piston flag, placed elsewhere with its data)
     * is the same connector: it keeps its id and its network, even where it touches nothing. Not taken for a copy.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aMovedConnectorKeepsItsNetworkAndId(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        UUID network = net(helper, new BlockPos(1, 1, 1));
        var connector = (io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity) be(helper, new BlockPos(3, 1, 1));
        UUID id = connector.connectorId();
        var registries = helper.getLevel().registryAccess();
        var data = connector.saveWithFullMetadata(registries);
        var level = helper.getLevel();
        level.setBlock(helper.absolutePos(new BlockPos(3, 1, 1)), Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_MOVE_BY_PISTON | net.minecraft.world.level.block.Block.UPDATE_ALL);
        BlockPos to = helper.absolutePos(new BlockPos(3, 1, 6));
        level.setBlock(to, ModBlocks.CONNECTOR.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.getBlockEntity(to).loadWithComponents(data, registries);
        helper.runAfterDelay(5, () -> {
            var moved = (io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity) be(helper, new BlockPos(3, 1, 6));
            check(helper, id.equals(moved.connectorId()), "the moved connector got a new id (taken for a copy)");
            check(helper, network.equals(net(helper, new BlockPos(3, 1, 6))), "the moved connector lost its network");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void differentNetworksDoNotJoin(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(1, 1, 2), ModBlocks.CABLE.get(), Direction.NORTH);
        place(helper, new BlockPos(1, 1, 5), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(1, 1, 4), ModBlocks.CABLE.get(), Direction.SOUTH);
        // The middle cable touches both networks; it connects to the one it was placed against.
        place(helper, new BlockPos(1, 1, 3), ModBlocks.CABLE.get(), Direction.NORTH);

        UUID a = net(helper, new BlockPos(1, 1, 1));
        UUID b = net(helper, new BlockPos(1, 1, 5));
        check(helper, !a.equals(b), "controllers share a network");
        check(helper, a.equals(net(helper, new BlockPos(1, 1, 3))), "middle cable did not connect to the network it was placed against");
        check(helper, b.equals(net(helper, new BlockPos(1, 1, 4))), "network B's cable changed network");
        check(helper, !Topology.connected(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 3)), Direction.SOUTH),
                "cable connected across two networks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disconnectAndReconnect(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        BlockPos middle = helper.absolutePos(new BlockPos(2, 1, 1));

        Topology.toggle(helper.getLevel(), middle, Direction.EAST, null);
        check(helper, Topology.jointState(helper.getLevel(), middle, Direction.EAST) == Topology.JointState.DISCONNECTED, "joint not disconnected");
        check(helper, !Topology.component(helper.getLevel(), middle, null).blocks().contains(middle.east()), "disconnected section still connected");
        // Network ids are sticky: the disconnected cable keeps its network.
        check(helper, net(helper, new BlockPos(1, 1, 1)).equals(net(helper, new BlockPos(3, 1, 1))), "disconnected cable lost its network");

        Topology.toggle(helper.getLevel(), middle, Direction.EAST, null);
        check(helper, Topology.connected(helper.getLevel(), middle, Direction.EAST), "joint not connected again");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void controllerlessSectionAdoptsController(GameTestHelper helper) {
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), null);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        check(helper, net(helper, new BlockPos(2, 1, 1)) == null, "cable without controller has a network");
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), Direction.EAST);
        UUID id = net(helper, new BlockPos(1, 1, 1));
        check(helper, id.equals(net(helper, new BlockPos(3, 1, 1))), "section did not adopt the controller's network");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void orphanLifecycle(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.BARREL);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        Network network = network(helper, new BlockPos(1, 1, 1));
        check(helper, network.devices().size() == 2, "expected chest and barrel as devices, got " + network.devices().size());

        var chest = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1)));
        var barrel = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)));
        network.device(chest).setName("Ore chest");

        helper.setBlock(new BlockPos(2, 1, 1), Blocks.AIR);
        DeviceRecord named = network.device(chest);
        check(helper, named != null && named.state() == DeviceRecord.State.ORPHANED, "named device should be kept as an orphan");
        check(helper, network.device(barrel) == null, "unnamed device should be dropped");
        check(helper, network.connectors().isEmpty(), "connector record not removed");

        // A new connector at the same spot picks the name back up.
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        check(helper, network.device(chest).state() == DeviceRecord.State.ACTIVE, "orphan not reattached");
        check(helper, "Ore chest".equals(network.device(chest).name()), "name lost");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingDeviceSideDropsDevice(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        Network network = network(helper, new BlockPos(1, 1, 1));
        BlockPos connector = helper.absolutePos(new BlockPos(2, 1, 1));

        Topology.toggle(helper.getLevel(), connector, Direction.EAST, null);
        check(helper, !((ConnectorBlockEntity) be(helper, new BlockPos(2, 1, 1))).isDeviceEnabled(Direction.EAST), "side not disabled");
        check(helper, network.devices().isEmpty(), "disabled device still listed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacedControllerKeepsNetwork(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        UUID id = net(helper, new BlockPos(1, 1, 1));
        // Something worth keeping; an empty network is deleted along with its controller.
        network(helper, new BlockPos(1, 1, 1)).sheets().add(new io.github.leadassimilator.reticulum.graph.Sheet(UUID.randomUUID(), "work"));

        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        check(helper, NetworkManager.network(helper.getLevel().getServer(), id).controller() == null, "controller position not cleared");
        check(helper, id.equals(net(helper, new BlockPos(2, 1, 1))), "cable lost its network with the controller");

        // Simulates placing the dropped controller item, which carries the network id.
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get());
        be(helper, new BlockPos(1, 1, 1)).setNetworkId(id);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), Direction.EAST, null);
        check(helper, id.equals(net(helper, new BlockPos(1, 1, 1))), "replaced controller started a new network");
        check(helper, NetworkManager.network(helper.getLevel().getServer(), id).controller() != null, "controller position not set");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lookAtDescribesNamedDevice(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        Network network = network(helper, new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 1));
        DeviceRecord record = network.device(Devices.global(helper.getLevel(), chest));
        record.setName("Ore chest");
        record.setFaceName(io.github.leadassimilator.reticulum.network.FaceKey.UP, "Input");

        var lines = io.github.leadassimilator.reticulum.lookat.LookAt.describe(helper.getLevel(), chest, Direction.UP,
                helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL));
        String text = lines.stream().map(net.minecraft.network.chat.Component::getString).reduce("", (a, b) -> a + "|" + b);
        check(helper, text.contains("Ore chest"), "device name missing: " + text);
        check(helper, text.contains("Input"), "face name missing: " + text);
        check(helper, text.contains(network.name()), "network name missing: " + text);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void toolTargetsTheArmUnderTheCrosshair(GameTestHelper helper) {
        BlockPos pos = BlockPos.ZERO;
        // Looking south into the north face of a cable, from 2 blocks away.
        java.util.function.BiFunction<Double, Double, Direction> aim = (x, y) -> {
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(x, y, -2);
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(x, y, 3);
            return Topology.targetSide(pos, false, from, to, Direction.NORTH);
        };
        check(helper, aim.apply(0.5, 0.5) == Direction.NORTH, "center should target the north arm");
        check(helper, aim.apply(0.7, 0.5) == Direction.EAST, "just right of the core should target the east arm, got " + aim.apply(0.7, 0.5));
        check(helper, aim.apply(0.3, 0.5) == Direction.WEST, "just left of the core should target the west arm");
        check(helper, aim.apply(0.5, 0.7) == Direction.UP, "just above the core should target the up arm");
        check(helper, aim.apply(0.9, 0.9) == Direction.EAST || aim.apply(0.9, 0.9) == Direction.UP, "corner should pick a nearby arm");
        // Looking down steeply from above and to the side, past the east arm: must not fall back to the top.
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(1.6, 1.6, -1.0);
        net.minecraft.world.phys.Vec3 through = new net.minecraft.world.phys.Vec3(0.95, 0.35, 0.5);
        Direction steep = Topology.targetSide(pos, true, eye, eye.add(through.subtract(eye).scale(3)), Direction.UP);
        check(helper, steep == Direction.EAST, "steep look past the east arm should target east, got " + steep);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void newControllerTakesOverOrphanedNetwork(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        Network network = network(helper, new BlockPos(1, 1, 1));
        network.sheets().add(new io.github.leadassimilator.reticulum.graph.Sheet(UUID.randomUUID(), "keep me"));
        // Broken in creative: no item, so the new controller has no remembered network.
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), Direction.EAST);
        check(helper, network.id().equals(net(helper, new BlockPos(1, 1, 1))), "new controller started a new network instead of taking over");
        check(helper, network.controller() != null, "controller position not set");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyNetworkGoesWithItsController(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        UUID id = net(helper, new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        check(helper, NetworkManager.network(helper.getLevel().getServer(), id) == null, "empty network left behind");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void connectingOrphanedNetworkMergesIt(GameTestHelper helper) {
        // Network A with a named device, then its controller is lost.
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.BARREL);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        Network a = network(helper, new BlockPos(1, 1, 1));
        a.sheets().add(new io.github.leadassimilator.reticulum.graph.Sheet(UUID.randomUUID(), "from A"));
        var barrel = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)));
        a.device(barrel).setName("Logs");
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);

        // Network B, built separately, then its cable is run up to A's cable.
        place(helper, new BlockPos(1, 1, 5), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 5), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(2, 1, 4), ModBlocks.CABLE.get(), Direction.SOUTH);
        place(helper, new BlockPos(2, 1, 3), ModBlocks.CABLE.get(), Direction.SOUTH);
        place(helper, new BlockPos(2, 1, 2), ModBlocks.CABLE.get(), Direction.SOUTH);
        Network b = network(helper, new BlockPos(1, 1, 5));
        check(helper, !a.id().equals(b.id()), "setup: networks should differ");
        BlockPos joint = helper.absolutePos(new BlockPos(2, 1, 2));
        check(helper, !Topology.connected(helper.getLevel(), joint, Direction.NORTH), "setup: A and B should not connect by themselves");

        Topology.toggle(helper.getLevel(), joint, Direction.NORTH, null);
        check(helper, NetworkManager.network(helper.getLevel().getServer(), a.id()) == b, "A was not merged into B");
        check(helper, b.id().equals(net(helper, new BlockPos(3, 1, 1))), "A's connector not on B");
        check(helper, b.device(barrel) != null && "Logs".equals(b.device(barrel).name()), "device name lost in the merge");
        check(helper, b.device(barrel).state() == io.github.leadassimilator.reticulum.network.DeviceRecord.State.ACTIVE, "merged device not active");
        check(helper, b.sheets().stream().anyMatch(sh -> sh.name().equals("from A")), "A's sheet lost in the merge");
        helper.succeed();
    }
}
