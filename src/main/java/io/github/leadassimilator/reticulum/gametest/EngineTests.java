package io.github.leadassimilator.reticulum.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.Redstone;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Engine tests. Each builds a controller with a row of connectors along x (starting at x = 3), each with a device
 * directly south of it, then wires a sheet between those devices and checks what moved.
 */
@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class EngineTests {
    private EngineTests() {
    }

    /** A test network with one device per connector; {@code devices} are relative positions. */
    private record Rig(GameTestHelper helper, Network network, List<BlockPos> devices, Sheet sheet) {
        Container container(int i) {
            return (Container) helper.getBlockEntity(devices.get(i));
        }

        NodeSettings.Port port(int i, ResourceKind resource) {
            return new NodeSettings.Port(resource, Devices.global(helper.getLevel(), helper.absolutePos(devices.get(i))), List.of(), Optional.empty());
        }

        /** A device node with one output (a source), every tick. */
        Node source(int i, ResourceKind resource, List<NodeSettings.Limit> keep, int perOperation) {
            return output(i, new NodeSettings.Output(resource, List.of(), Optional.empty(), keep, perOperation, 1, 0));
        }

        Node output(int i, NodeSettings.Output output) {
            return sheet.add(Node.of(new NodeSettings.DeviceSettings(port(i, output.resource()).device(), List.of(), List.of(output))));
        }

        /** A device node with one input (a sink). */
        Node sink(int i, ResourceKind resource, List<NodeSettings.Limit> caps) {
            return sheet.add(Node.of(new NodeSettings.DeviceSettings(port(i, resource).device(),
                    List.of(new NodeSettings.Input(resource, List.of(), Optional.empty(), caps)), List.of())));
        }

        Node node(NodeSettings settings) {
            return sheet.add(Node.of(settings));
        }

        void wire(Node from, Node to) {
            sheet.connect(Wire.of(from, to));
        }

        void wire(Node from, int output, Node to) {
            sheet.connect(new Wire(UUID.randomUUID(), from.id(), output, to.id(), 0));
        }

        int count(int i, net.minecraft.world.item.Item item) {
            return container(i).countItem(item);
        }

        GlobalPos device(int i) {
            return Devices.global(helper.getLevel(), helper.absolutePos(devices.get(i)));
        }

        /** A signal wire into a signal node's input, or into any node's gate with {@link Wire#GATE}. */
        void signal(Node from, Node to, int input) {
            sheet.connect(new Wire(UUID.randomUUID(), from.id(), 0, to.id(), input, 0));
        }

        NetworkRuntime runtime() {
            return NetworkTicker.runtime(network.id());
        }

        CompiledSheet compiled() {
            return runtime().compiled(sheet, network);
        }
    }

    private static BlockState lever(boolean powered) {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, powered);
    }

    private static Rig rig(GameTestHelper helper, Block... devices) {
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CABLE.get(), Direction.WEST);
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < devices.length; i++) {
            BlockPos device = new BlockPos(3 + i, 1, 2);
            helper.setBlock(device, devices[i]);
            positions.add(device);
            place(helper, new BlockPos(3 + i, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        }
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
        if (network == null || network.devices().size() != devices.length) {
            helper.fail("Rig did not build: " + (network == null ? "no network" : network.devices().size() + " devices"));
        }
        Sheet sheet = new Sheet(UUID.randomUUID(), "test");
        sheet.setInterval(1);
        network.sheets().add(sheet);
        return new Rig(helper, network, positions, sheet);
    }

    private static void place(GameTestHelper helper, BlockPos rel, Block block, Direction against) {
        helper.setBlock(rel, block);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(rel), against, null);
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    // ---------------------------------------------------------------- items

    @GameTest(template = "empty")
    public static void itemsMoveWithoutDuplication(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        rig.container(0).setItem(1, new ItemStack(Items.IRON_INGOT, 36));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(0, Items.IRON_INGOT) + rig.count(1, Items.IRON_INGOT) == 100, "items duplicated or lost");
            check(helper, rig.count(1, Items.IRON_INGOT) == 100, "not all moved yet");
        });
    }

    @GameTest(template = "empty")
    public static void filterSendsMatchesAndRejectsApart(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 10));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        Node filter = rig.node(new NodeSettings.FilterSettings("tag:c:ingots"));
        rig.wire(source, filter);
        rig.wire(filter, Wire.MATCH, rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.wire(filter, Wire.REJECT, rig.sink(2, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 10 && rig.count(1, Items.DIRT) == 0, "ingots not all in the match barrel");
            check(helper, rig.count(2, Items.DIRT) == 10 && rig.count(2, Items.IRON_INGOT) == 0, "dirt not all in the reject barrel");
        });
    }

    @GameTest(template = "empty")
    public static void keepRuleLeavesItemsBehind(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        List<NodeSettings.Limit> keep = List.of(new NodeSettings.Limit(NodeSettings.Scope.PER_TYPE, 16, ""));
        rig.wire(rig.source(0, ResourceKind.ITEM, keep, 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(30, () -> {
            check(helper, rig.count(0, Items.COBBLESTONE) == 16, "source should keep 16, has " + rig.count(0, Items.COBBLESTONE));
            check(helper, rig.count(1, Items.COBBLESTONE) == 48, "sink should have 48, has " + rig.count(1, Items.COBBLESTONE));
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void capStopsInserting(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.container(1).setItem(5, new ItemStack(Items.DIRT, 4));
        // At most 20 items in the sink in total; 4 dirt already there.
        List<NodeSettings.Limit> caps = List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, 20, ""));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, caps));
        helper.runAfterDelay(30, () -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 16, "sink should take 16 cobblestone, has " + rig.count(1, Items.COBBLESTONE));
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void rateLimitSlowsItems(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        Node rate = rig.node(new NodeSettings.RateLimitSettings(20));
        rig.wire(source, rate);
        rig.wire(rate, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(20, () -> {
            int moved = rig.count(1, Items.COBBLESTONE);
            check(helper, moved > 0 && moved <= 21, "20 items/s should move at most ~20 in 20 ticks, moved " + moved);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- redstone

    @GameTest(template = "empty")
    public static void leverSwitchesLampThroughNot(GameTestHelper helper) {
        // A barrel keeps the lever and the lamp apart, so only the network links them.
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(true));
        Node in = rig.node(new NodeSettings.RedstoneInSettings(rig.device(0), Optional.empty(), false));
        Node not = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.NOT));
        Node out = rig.node(new NodeSettings.RedstoneOutSettings(rig.device(2)));
        rig.signal(in, not, 0);
        rig.signal(not, out, 0);
        BlockPos lamp = rig.devices().get(2);
        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    check(helper, rig.compiled().errors().isEmpty(),
                            "errors: " + rig.compiled().errors());
                    check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lamp lit while the lever is on");
                    helper.setBlock(rig.devices().get(0), lever(false));
                })
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lamp not lit after the lever went off"))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void connectorFindsDevicesItMissed(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        // Placed without any block update, as if it changed while the connector was unloaded.
        BlockPos lamp = helper.absolutePos(new BlockPos(3, 1, 0));
        helper.getLevel().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        GlobalPos device = Devices.global(helper.getLevel(), lamp);
        helper.succeedWhen(() -> check(helper, rig.network().device(device) != null, "the connector never found the lamp"));
    }

    @GameTest(template = "empty")
    public static void gateWireSwitchesAFlow(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.LEVER);
        helper.setBlock(rig.devices().get(2), lever(false));
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        rig.wire(source, rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.signal(rig.node(new NodeSettings.RedstoneInSettings(rig.device(2), Optional.empty(), false)), source, Wire.GATE);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "items moved through a closed gate");
                    helper.setBlock(rig.devices().get(2), lever(true));
                })
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 64, "items didn't move once the gate opened"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void sheetRunsWhileControllerSignalIsLow(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.REDSTONE_BLOCK);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.sheet().setRedstone(new Redstone(Redstone.Mode.LOW, Redstone.Source.DEFAULT, Optional.empty(), Optional.empty(), Optional.empty(), ""));
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "sheet ran while its controller was powered");
                    helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
                })
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 64, "sheet didn't run once the signal went low"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void counterPublishesAndDelayLoopCompiles(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        Node timer = rig.node(new NodeSettings.TimerSettings(2));
        Node counter = rig.node(new NodeSettings.CounterSettings());
        Node compare = rig.node(new NodeSettings.CompareSettings(NodeSettings.CompareOp.GE, 3));
        Node publish = rig.node(new NodeSettings.PublishSettings("done"));
        rig.signal(timer, counter, 0);
        rig.signal(counter, compare, 0);
        rig.signal(compare, publish, 0);
        // A clock made of a NOT fed by its own output one tick late: a loop, but through a Delay.
        Node not = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.NOT));
        Node delay = rig.node(new NodeSettings.DelaySettings(1));
        rig.signal(not, delay, 0);
        rig.signal(delay, not, 0);
        helper.succeedWhen(() -> {
            check(helper, rig.compiled().errors().isEmpty(),
                    "errors: " + rig.compiled().errors());
            check(helper, rig.runtime().networkSignals().getOrDefault("done", 0L) == 15, "counter didn't reach 3 and publish");
        });
    }

    @GameTest(template = "empty")
    public static void signalLoopWithoutMemoryIsRefused(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        Node a = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.NOT));
        Node b = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.OR));
        rig.signal(a, b, 0);
        rig.signal(b, a, 0);
        helper.succeedWhen(() -> {
            CompiledSheet compiled = rig.compiled();
            check(helper, compiled != null && compiled.errors().stream().anyMatch(e -> e.contains("loop")), "loop not reported");
        });
    }

    // ---------------------------------------------------------------- wireless

    @GameTest(template = "empty")
    public static void wirelessConnectorNeedsAnAntenna(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        // A connector no cable joins, bound to the rig's network, with its own barrel.
        BlockPos connector = new BlockPos(7, 1, 4);
        BlockPos barrel = new BlockPos(7, 1, 5);
        helper.setBlock(barrel, Blocks.BARREL);
        place(helper, connector, ModBlocks.CONNECTOR.get(), null);
        Topology.bindWireless(helper.getLevel(), helper.absolutePos(connector), rig.network(), null);
        check(helper, rig.network().devices().size() == 2, "wireless connector's barrel isn't a device: " + rig.network().devices().size());
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        GlobalPos far = Devices.global(helper.getLevel(), helper.absolutePos(barrel));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sheet().add(Node.of(new NodeSettings.DeviceSettings(far,
                List.of(new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.empty(), List.of())), List.of()))));
        Container target = (Container) helper.getBlockEntity(barrel);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, target.countItem(Items.COBBLESTONE) == 0, "items reached a wireless connector without an antenna");
                    check(helper, rig.runtime().outOfRange(far), "device not reported out of range");
                    ((ControllerBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1))).setAntennaTier(1);
                })
                .thenWaitUntil(() -> check(helper, target.countItem(Items.COBBLESTONE) == 64, "items didn't move once the antenna was in"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void wiredConnectorCannotBeBound(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        Network other = NetworkManager.create(helper.getLevel().getServer(), null);
        Topology.bindWireless(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1)), other, null);
        check(helper, Topology.network(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1))) == rig.network(),
                "a cable-joined connector was moved to another network");
        NetworkManager.delete(helper.getLevel().getServer(), other);
        helper.succeed();
    }

    // ---------------------------------------------------------------- AE2

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
        java.util.function.Function<Integer, UUID> connector = x -> rig.network().connectors().stream()
                .filter(c -> c.pos().pos().equals(helper.absolutePos(new BlockPos(x, 1, 1)))).findFirst().orElseThrow().id();
        Node a = rig.node(new NodeSettings.MePortSettings(connector.apply(3), Direction.SOUTH));
        Node b = rig.node(new NodeSettings.MePortSettings(connector.apply(5), Direction.SOUTH));
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
        java.util.function.Function<BlockPos, UUID> connector = pos -> rig.network().connectors().stream()
                .filter(c -> c.pos().pos().equals(pos)).findFirst().orElseThrow().id();
        Node a = rig.node(new NodeSettings.MePortSettings(connector.apply(first), Direction.SOUTH));
        Node b = rig.node(new NodeSettings.MePortSettings(connector.apply(second), Direction.NORTH));
        rig.sheet().connect(Wire.of(a, b));
        helper.succeedWhen(() -> {
            var sides = io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration.sides(helper.getLevel(), first);
            check(helper, !sides.contains(Direction.NORTH), "an ME endpoint faces a storage bus with no cable: " + sides);
            check(helper, rig.network().device(Devices.global(helper.getLevel(), first.north())) == null,
                    "a storage bus with no cable counts as a device");
            check(helper, helper.getLevel().getBlockState(first).getValue(
                    io.github.leadassimilator.reticulum.block.ConnectorBlock.LINKS.get(Direction.NORTH)) != io.github.leadassimilator.reticulum.block.Link.DEVICE,
                    "the connector shows a plug towards a storage bus with no cable");
            String usage = rig.runtime().meUsage().get(b.id());
            check(helper, usage != null && usage.endsWith("/8"), "the glass cable side shows " + usage + " instead of x/8");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void mePortCountsChannelsOnItsCable(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        // A powered ME controller on the first connector; a glass cable with two interfaces on the second.
        Rig rig = rig(helper, Ae2TestSupport.block("controller"), Blocks.BARREL);
        helper.setBlock(new BlockPos(3, 1, 3), Ae2TestSupport.block("creative_energy_cell"));
        BlockPos first = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos second = helper.absolutePos(new BlockPos(4, 1, 1));
        Ae2TestSupport.use(helper.getLevel(), "fluix_glass_cable", second, Direction.NORTH);
        helper.setBlock(new BlockPos(4, 2, 0), Ae2TestSupport.block("interface"));
        helper.setBlock(new BlockPos(5, 1, 0), Ae2TestSupport.block("interface"));
        java.util.function.Function<BlockPos, UUID> connector = pos -> rig.network().connectors().stream()
                .filter(c -> c.pos().pos().equals(pos)).findFirst().orElseThrow().id();
        Node a = rig.node(new NodeSettings.MePortSettings(connector.apply(first), Direction.SOUTH));
        Node b = rig.node(new NodeSettings.MePortSettings(connector.apply(second), Direction.NORTH));
        rig.sheet().connect(Wire.of(a, b));
        helper.succeedWhen(() -> {
            String usage = rig.runtime().meUsage().get(b.id());
            check(helper, "2/8".equals(usage), "the glass cable carries two interfaces' channels but shows " + usage);
        });
    }

    @GameTest(template = "empty")
    public static void ae2CableShowsItsCableAsIcon(GameTestHelper helper) {
        if (!Mods.AE2) {
            helper.succeed();
            return;
        }
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        ItemStack cable = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ae2", "fluix_covered_dense_cable")));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, cable);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false);
        cable.useOn(new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
        helper.succeedWhen(() -> {
            ItemStack icon = Devices.icon(helper.getLevel(), pos);
            check(helper, icon.is(cable.getItem()), "icon is " + icon + " for " + helper.getLevel().getBlockState(pos));
        });
    }

    @GameTest(template = "empty")
    public static void antennaTiersReach(GameTestHelper helper) {
        var overworld = net.minecraft.world.level.Level.OVERWORLD;
        var nether = net.minecraft.world.level.Level.NETHER;
        GlobalPos controller = GlobalPos.of(overworld, BlockPos.ZERO);
        GlobalPos near = GlobalPos.of(overworld, new BlockPos(100, 0, 0));
        GlobalPos far = GlobalPos.of(overworld, new BlockPos(1000, 0, 0));
        GlobalPos elsewhere = GlobalPos.of(nether, BlockPos.ZERO);
        check(helper, !NetworkRuntime.inRange(0, controller, near), "no antenna reached");
        check(helper, NetworkRuntime.inRange(1, controller, near) && !NetworkRuntime.inRange(1, controller, far), "tier 1 range wrong");
        check(helper, NetworkRuntime.inRange(2, controller, far) && !NetworkRuntime.inRange(2, controller, elsewhere), "tier 2 range wrong");
        check(helper, NetworkRuntime.inRange(3, controller, elsewhere), "tier 3 didn't reach another dimension");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disabledSheetMovesNothing(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.sheet().setEnabled(false);
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 0, "disabled sheet moved items");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void splitterRoundRobinsBetweenSinks(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 8);
        Node splitter = rig.node(new NodeSettings.SplitterSettings(NodeSettings.SplitMode.ROUND_ROBIN));
        rig.wire(source, splitter);
        rig.wire(splitter, rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.wire(splitter, rig.sink(2, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 32 && rig.count(2, Items.COBBLESTONE) == 32,
                    "expected 32/32, got " + rig.count(1, Items.COBBLESTONE) + "/" + rig.count(2, Items.COBBLESTONE));
        });
    }

    // ---------------------------------------------------------------- kits

    private static Node kit(Rig rig) {
        return rig.node(new NodeSettings.KitSettings(List.of(
                new NodeSettings.KitLine("id:minecraft:iron_ingot", 4),
                new NodeSettings.KitLine("id:minecraft:coal", 1)), 1));
    }

    @GameTest(template = "empty")
    public static void kitWaitsForAllParts(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        Node kit = kit(rig);
        rig.wire(source, kit);
        rig.wire(kit, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(10, () -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 0, "kit moved without its coal");
            rig.container(0).setItem(1, new ItemStack(Items.COAL, 1));
            helper.runAfterDelay(10, () -> {
                check(helper, rig.count(1, Items.IRON_INGOT) == 4 && rig.count(1, Items.COAL) == 1,
                        "expected exactly one kit (4 iron + 1 coal), got " + rig.count(1, Items.IRON_INGOT) + " iron, " + rig.count(1, Items.COAL) + " coal");
                check(helper, rig.count(0, Items.IRON_INGOT) == 6, "source should have 6 iron left");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void kitNeedsRoomForEveryPart(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        rig.container(0).setItem(1, new ItemStack(Items.COAL, 5));
        // Fill every sink slot but one, so iron fits and coal doesn't.
        Container sink = rig.container(1);
        for (int slot = 0; slot < sink.getContainerSize() - 1; slot++) {
            sink.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        Node kit = kit(rig);
        rig.wire(source, kit);
        rig.wire(kit, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 0 && rig.count(1, Items.COAL) == 0, "part of a kit was delivered");
            check(helper, rig.count(0, Items.IRON_INGOT) == 10 && rig.count(0, Items.COAL) == 5, "source lost items");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void kitGathersPartsFromTwoSources(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        rig.container(1).setItem(0, new ItemStack(Items.COAL, 1));
        Node kit = kit(rig);
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), kit);
        rig.wire(rig.source(1, ResourceKind.ITEM, List.of(), 64), kit);
        rig.wire(kit, rig.sink(2, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> check(helper, rig.count(2, Items.IRON_INGOT) == 4 && rig.count(2, Items.COAL) == 1, "kit not assembled from two sources"));
    }

    // ---------------------------------------------------------------- fluids and sharing

    @GameTest(template = "empty")
    public static void fluidMovesBetweenCauldrons(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.WATER_CAULDRON, Blocks.CAULDRON);
        helper.setBlock(rig.devices().get(0), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        rig.wire(rig.source(0, ResourceKind.FLUID, List.of(), 0), rig.sink(1, ResourceKind.FLUID, List.of()));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.CAULDRON, rig.devices().get(0));
            helper.assertBlockPresent(Blocks.WATER_CAULDRON, rig.devices().get(1));
        });
    }

    @GameTest(template = "empty")
    public static void chemicalMovesBetweenMekanismTanks(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        Block tank = MekanismTestSupport.block("basic_chemical_tank");
        Rig rig = rig(helper, tank, tank);
        BlockPos from = helper.absolutePos(rig.devices().get(0));
        BlockPos to = helper.absolutePos(rig.devices().get(1));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), from);
        MekanismTestSupport.placedByPlayer(helper.getLevel(), to);
        long filled = MekanismTestSupport.fill(helper.getLevel(), from, "hydrogen", 4000);
        check(helper, filled == 4000, "could fill only " + filled + " mB of hydrogen");
        rig.wire(rig.source(0, ResourceKind.CHEMICAL, List.of(), 0), rig.sink(1, ResourceKind.CHEMICAL, List.of()));
        helper.succeedWhen(() -> {
            long left = MekanismTestSupport.stored(helper.getLevel(), from);
            long moved = MekanismTestSupport.stored(helper.getLevel(), to);
            check(helper, left == 0 && moved == 4000, "source has " + left + " mB, target " + moved + " mB");
        });
    }

    @GameTest(template = "empty")
    public static void evenShareHandsOutRemainder(GameTestHelper helper) {
        long[] share = shareFor(10, new long[] {100, 100, 100}, false);
        check(helper, share[0] + share[1] + share[2] == 10 && share[0] == 4 && share[1] == 3 && share[2] == 3, "uneven split " + java.util.Arrays.toString(share));
        share = shareFor(100, new long[] {10, 100, 100}, false);
        check(helper, share[0] == 10 && share[1] == 45 && share[2] == 45, "unused share not passed on " + java.util.Arrays.toString(share));
        share = shareFor(100, new long[] {60, 60}, true);
        check(helper, share[0] == 60 && share[1] == 40, "priority fill wrong " + java.util.Arrays.toString(share));
        helper.succeed();
    }

    private static long[] shareFor(long supply, long[] demand, boolean inOrder) {
        try {
            var method = Class.forName("io.github.leadassimilator.reticulum.engine.ContinuousMover")
                    .getDeclaredMethod("share", long.class, long[].class, boolean.class);
            method.setAccessible(true);
            return (long[]) method.invoke(null, supply, demand, inOrder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTest(template = "empty")
    public static void stackModeMovesOneStackPerOperation(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.ENDER_PEARL, 16));
        rig.container(0).setItem(1, new ItemStack(Items.ENDER_PEARL, 16));
        // Interval 20: as soon as the first operation has moved anything, it must have moved exactly one stack.
        rig.sheet().setInterval(20);
        rig.wire(rig.output(0, new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 0, 20, 0)),
                rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.ENDER_PEARL) > 0, "nothing moved yet"))
                .thenExecute(() -> check(helper, rig.count(1, Items.ENDER_PEARL) == 16, "one stack of pearls is 16, moved " + rig.count(1, Items.ENDER_PEARL)))
                .thenSucceed();
    }

    /** A source on one face follows that face's rules: a furnace's bottom gives up only its output, not its ore. */
    @GameTest(template = "empty")
    public static void faceSourceRespectsSideRules(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.FURNACE, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_ORE, 8));
        rig.container(0).setItem(2, new ItemStack(Items.IRON_INGOT, 5));
        NodeSettings.Output bottom = new NodeSettings.Output(ResourceKind.ITEM, List.of(io.github.leadassimilator.reticulum.network.FaceKey.DOWN),
                Optional.empty(), List.of(), 64, 1, 0);
        rig.wire(rig.output(0, bottom), rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 5, "furnace output not taken");
            check(helper, rig.count(1, Items.IRON_ORE) == 0, "furnace input was taken through the bottom face");
            helper.succeed();
        });
    }

    /** A device node is fed and emptied at once: A -> B -> C with B as one node. */
    @GameTest(template = "empty")
    public static void deviceNodeIsBothSinkAndSource(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        Node middle = rig.node(new NodeSettings.DeviceSettings(rig.port(1, ResourceKind.ITEM).device(),
                List.of(new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.empty(), List.of())),
                List.of(new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 0, 1, 0))));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), middle);
        rig.wire(middle, rig.sink(2, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(2, Items.COBBLESTONE) == 32, "items did not pass through the device node, C has " + rig.count(2, Items.COBBLESTONE));
            check(helper, rig.count(0, Items.COBBLESTONE) + rig.count(1, Items.COBBLESTONE) + rig.count(2, Items.COBBLESTONE) == 32, "items duplicated or lost");
        });
    }

    private static io.github.leadassimilator.reticulum.engine.Status status(Rig rig, Node node) {
        return io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(rig.network().id()).statuses().get(node.id());
    }

    @GameTest(template = "empty")
    public static void idleReasonSourceEmpty(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        rig.wire(source, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(5, () -> {
            check(helper, status(rig, source) == io.github.leadassimilator.reticulum.engine.Status.SOURCE_EMPTY,
                    "expected source_empty, got " + status(rig, source));
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void idleReasonTargetFull(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        Container sink = rig.container(1);
        for (int slot = 0; slot < sink.getContainerSize(); slot++) {
            sink.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        rig.wire(source, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(5, () -> {
            check(helper, status(rig, source) == io.github.leadassimilator.reticulum.engine.Status.TARGET_FULL,
                    "expected target_full, got " + status(rig, source));
            sink.setItem(0, ItemStack.EMPTY);
            helper.runAfterDelay(20, () -> {
                check(helper, status(rig, source) == null || rig.count(1, Items.COBBLESTONE) == 8,
                        "status not cleared once items moved: " + status(rig, source));
                helper.succeed();
            });
        });
    }

    /** A sub-sheet holding "input -> filter (ingots) -> output", used between a barrel and another barrel. */
    @GameTest(template = "empty")
    public static void subSheetIsPastedIn(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 5));

        Sheet template = new Sheet(UUID.randomUUID(), "only ingots");
        template.setSubsheet(true);
        Node in = template.add(Node.of(new NodeSettings.SheetPinSettings(true, "in")));
        Node filter = template.add(Node.of(new NodeSettings.FilterSettings("tag:c:ingots")));
        Node out = template.add(Node.of(new NodeSettings.SheetPinSettings(false, "out")));
        template.connect(Wire.of(in, filter));
        template.connect(Wire.of(filter, out));
        rig.network().sheets().add(template);

        Node sub = rig.node(new NodeSettings.SubSheetSettings(template.id()));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), sub);
        rig.wire(sub, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 5, "ingots did not pass through the sub-sheet");
            check(helper, rig.count(1, Items.DIRT) == 0 && rig.count(0, Items.DIRT) == 5, "the sub-sheet's filter was not applied");
        });
    }

    @GameTest(template = "empty")
    public static void folderSwitchedOffStopsItsSheets(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.sheet().setFolder("smelting/ores");
        rig.network().disabledFolders().add("smelting");
        helper.runAfterDelay(10, () -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 0, "a sheet in a switched-off folder ran");
            rig.network().disabledFolders().clear();
            helper.runAfterDelay(10, () -> {
                check(helper, rig.count(1, Items.COBBLESTONE) == 8, "the sheet did not run once its folder was back on");
                helper.succeed();
            });
        });
    }
}
