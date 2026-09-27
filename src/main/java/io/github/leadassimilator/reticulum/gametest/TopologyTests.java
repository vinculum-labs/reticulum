package io.github.leadassimilator.reticulum.gametest;

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

    /** Places a block the way a player would, clicking against the block on side {@code against} (may be null). */
    private static void place(GameTestHelper helper, BlockPos rel, Block block, @Nullable Direction against) {
        helper.setBlock(rel, block);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(rel), against, null);
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

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
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

    @GameTest(template = "empty")
    public static void differentNetworksDoNotJoin(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(1, 1, 2), ModBlocks.CABLE.get(), Direction.NORTH);
        place(helper, new BlockPos(1, 1, 5), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(1, 1, 4), ModBlocks.CABLE.get(), Direction.SOUTH);
        // The middle cable touches both networks; it joins the one it was placed against.
        place(helper, new BlockPos(1, 1, 3), ModBlocks.CABLE.get(), Direction.NORTH);

        UUID a = net(helper, new BlockPos(1, 1, 1));
        UUID b = net(helper, new BlockPos(1, 1, 5));
        check(helper, !a.equals(b), "controllers share a network");
        check(helper, a.equals(net(helper, new BlockPos(1, 1, 3))), "middle cable did not join the network it was placed against");
        check(helper, b.equals(net(helper, new BlockPos(1, 1, 4))), "network B's cable changed network");
        check(helper, !Topology.joined(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 3)), Direction.SOUTH),
                "cable joined across two networks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cutAndJoinAgain(GameTestHelper helper) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        place(helper, new BlockPos(3, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        BlockPos middle = helper.absolutePos(new BlockPos(2, 1, 1));

        Topology.toggle(helper.getLevel(), middle, Direction.EAST, null);
        check(helper, Topology.jointState(helper.getLevel(), middle, Direction.EAST) == Topology.JointState.CUT, "joint not cut");
        check(helper, !Topology.component(helper.getLevel(), middle, null).blocks().contains(middle.east()), "cut section still connected");
        // Network ids are sticky: the cut-off cable keeps its network.
        check(helper, net(helper, new BlockPos(1, 1, 1)).equals(net(helper, new BlockPos(3, 1, 1))), "cut-off cable lost its network");

        Topology.toggle(helper.getLevel(), middle, Direction.EAST, null);
        check(helper, Topology.joined(helper.getLevel(), middle, Direction.EAST), "joint not joined again");
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
}
