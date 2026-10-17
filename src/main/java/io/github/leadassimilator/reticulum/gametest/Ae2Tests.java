package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.EngineTests.rig;
import static io.github.leadassimilator.reticulum.gametest.TestSupport.check;

import appeng.api.networking.GridHelper;
import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.ConnectorBlock;
import io.github.leadassimilator.reticulum.block.Link;
import io.github.leadassimilator.reticulum.gametest.EngineTests.Rig;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration;
import io.github.leadassimilator.reticulum.lookat.LookAt;
import io.github.leadassimilator.reticulum.network.Devices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Game tests with AE2: ME links between AE2 networks. Left out of builds without AE2 support. */
public final class Ae2Tests {
    private Ae2Tests() {
    }

    @GameTest(template = "empty")
    public static void meLinkJoinsTwoAe2Networks(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        // An ME controller and an ME interface, kept apart by a barrel so only the link can join them.
        Rig rig = rig(helper, Ae2TestSupport.block("controller"), Blocks.BARREL, Ae2TestSupport.block("interface"));
        BlockPos controller = helper.absolutePos(rig.devices().get(0));
        BlockPos iface = helper.absolutePos(rig.devices().get(2));
        Node a = rig.meOut(rig.device(0));
        Node b = rig.meIn(rig.device(2));
        Wire[] link = new Wire[1];
        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    check(helper, !Ae2TestSupport.sameGrid(helper.getLevel(), controller, Direction.NORTH, iface, Direction.NORTH),
                            "joined before any link");
                    link[0] = Wire.of(a, b);
                    rig.sheet().connect(link[0]);
                })
                .thenWaitUntil(() -> check(helper, Ae2TestSupport.sameGrid(helper.getLevel(), controller, Direction.NORTH, iface, Direction.NORTH),
                        "the ME link didn't join the two AE2 networks; errors " + rig.compiled().errors()))
                .thenExecute(() -> rig.sheet().disconnect(link[0].id()))
                .thenWaitUntil(() -> check(helper, !Ae2TestSupport.sameGrid(helper.getLevel(), controller, Direction.NORTH, iface, Direction.NORTH),
                        "still joined after the link was removed"))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void mePortsReportCableCapacityAndSkipBareParts(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        Rig rig = rig(helper, Ae2TestSupport.block("controller"), Blocks.BARREL);
        BlockPos first = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos second = helper.absolutePos(new BlockPos(4, 1, 1));
        // A glass cable (8 channels) north of the second connector, placed by clicking the connector's north face.
        Ae2TestSupport.use(helper.getLevel(), "fluix_glass_cable", second, Direction.NORTH);
        // A storage bus with no cable north of the first connector, placed by clicking the connector's north face.
        Ae2TestSupport.use(helper.getLevel(), "storage_bus", first, Direction.NORTH);
        Node a = rig.meOut(rig.device(0));
        Node b = rig.meIn(Devices.global(helper.getLevel(), second.north()));
        rig.sheet().connect(Wire.of(a, b));
        helper.succeedWhen(() -> {
            var sides = Ae2Integration.sides(helper.getLevel(), first);
            check(helper, !sides.contains(Direction.NORTH), "an ME endpoint faces a storage bus with no cable: " + sides);
            check(helper, rig.network().device(Devices.global(helper.getLevel(), first.north())) == null,
                    "a storage bus with no cable counts as a device");
            check(helper, helper.getLevel().getBlockState(first).getValue(
                    ConnectorBlock.LINKS.get(Direction.NORTH)) != Link.DEVICE,
                    "the connector shows a plug towards a storage bus with no cable");
            var usage = rig.runtime().meUsage().get(b.id() + "/in/0");
            check(helper, usage != null && usage.max() == 8, "the glass cable side shows " + usage + " instead of x/8");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void mePortShowsTheFullestCableOnTheWayToTheController(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        BlockPos ae2c = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos shortbus = helper.absolutePos(new BlockPos(6, 1, 1));
        // ae2c: a glass cable on the way to a powered ME controller, carrying two interfaces' channels as well.
        Ae2TestSupport.use(helper.getLevel(), "fluix_glass_cable", ae2c, Direction.NORTH);
        helper.setBlock(new BlockPos(3, 2, 0), Ae2TestSupport.block("controller"));
        helper.setBlock(new BlockPos(3, 3, 0), Ae2TestSupport.block("creative_energy_cell"));
        helper.setBlock(new BlockPos(2, 1, 0), Ae2TestSupport.block("interface"));
        helper.setBlock(new BlockPos(4, 1, 0), Ae2TestSupport.block("interface"));
        // shortbus: a glass cable holding a storage bus, whose channel comes through the link.
        Ae2TestSupport.use(helper.getLevel(), "fluix_glass_cable", shortbus, Direction.NORTH);
        Ae2TestSupport.use(helper.getLevel(), "storage_bus", shortbus.north(), Direction.UP);
        Node a = rig.meOut(Devices.global(helper.getLevel(), ae2c.north()));
        Node b = rig.meIn(Devices.global(helper.getLevel(), shortbus.north()));
        rig.sheet().connect(Wire.of(a, b));
        helper.succeedWhen(() -> {
            var ae2cUsage = rig.runtime().meUsage().get(a.id() + "/out/0");
            var shortbusUsage = rig.runtime().meUsage().get(b.id() + "/in/0");
            check(helper, ae2cUsage != null && ae2cUsage.used() == 3 && ae2cUsage.max() == 8 && ae2cUsage.through() == 1,
                    "ae2c shows " + ae2cUsage + ", not 1 through and 3/8");
            check(helper, shortbusUsage != null && shortbusUsage.used() == 3 && shortbusUsage.max() == 8 && shortbusUsage.through() == 1,
                    "shortbus shows " + shortbusUsage + ", not 1 through and 3/8");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void smartCableOnAConnectorCountsChannelsThroughTheLink(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        // Near side: a smart cable on connector 0, on a powered controller. Far side: a glass cable on connector 2 with
        // two interfaces, reached only through the link. The smart cable carries their two channels.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        BlockPos smart = helper.absolutePos(new BlockPos(3, 1, 0));
        BlockPos far = helper.absolutePos(new BlockPos(5, 1, 0));
        Ae2TestSupport.use(helper.getLevel(), "fluix_smart_cable", smart.south(), Direction.NORTH);
        helper.setBlock(new BlockPos(3, 2, 0), Ae2TestSupport.block("controller"));
        helper.setBlock(new BlockPos(3, 3, 0), Ae2TestSupport.block("creative_energy_cell"));
        Ae2TestSupport.use(helper.getLevel(), "fluix_glass_cable", far.south(), Direction.NORTH);
        helper.setBlock(new BlockPos(5, 2, 0), Ae2TestSupport.block("interface"));
        helper.setBlock(new BlockPos(6, 1, 0), Ae2TestSupport.block("interface"));
        Node a = rig.meOut(Devices.global(helper.getLevel(), smart));
        Node b = rig.meIn(Devices.global(helper.getLevel(), far));
        rig.sheet().connect(Wire.of(a, b));
        helper.succeedWhen(() -> {
            var host = GridHelper.getNodeHost(helper.getLevel(), smart);
            var node = host == null ? null : host.getGridNode(null);
            check(helper, node != null, "no smart cable node");
            var toConnector = node.getInWorldConnections().get(Direction.SOUTH);
            int nodeUsed = node.getUsedChannels();
            int sideUsed = toConnector == null ? -1 : toConnector.getUsedChannels();
            check(helper, nodeUsed == 2 && sideUsed == 2,
                    "smart cable: node " + nodeUsed + "/" + node.getMaxChannels() + ", its connection to the connector " + sideUsed);
            // The far connector's look-at line shows the smart cable back at the controller, not its own cable's count.
            var player = FakePlayerFactory.getMinecraft(helper.getLevel());
            String lines = LookAt.describe(helper.getLevel(), far.south(), null, player)
                    .stream().map(Component::getString).toList().toString();
            check(helper, lines.contains("north 2/8"), "far connector says " + lines);
        });
    }

    @GameTest(template = "empty")
    public static void ae2CableShowsItsCableAsIcon(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        ItemStack cable = new ItemStack(BuiltInRegistries.ITEM.getValue(
                Identifier.fromNamespaceAndPath("ae2", "fluix_covered_dense_cable")));
        player.setItemInHand(InteractionHand.MAIN_HAND, cable);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false);
        cable.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.succeedWhen(() -> {
            ItemStack icon = Devices.icon(helper.getLevel(), pos);
            check(helper, icon.is(cable.getItem()), "icon is " + icon + " for " + helper.getLevel().getBlockState(pos));
        });
    }
}
