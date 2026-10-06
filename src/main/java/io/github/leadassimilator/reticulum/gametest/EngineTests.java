package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.place;
import static io.github.leadassimilator.reticulum.gametest.TestSupport.check;

import java.util.ArrayList;
import java.util.List;
import io.github.leadassimilator.reticulum.network.FaceKey;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity;
import io.github.leadassimilator.reticulum.engine.CompiledSheet;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
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

        /** The test sheet's node in its parent (it goes into the root the first time the tree is put right). */
        Node sheetNode() {
            network.ensureTree();
            return network.placement(sheet.id()).node();
        }

        /** Replaces the test sheet's node in its parent. */
        void sheetNode(java.util.function.UnaryOperator<Node> change) {
            network.ensureTree();
            Network.Placement placement = network.placement(sheet.id());
            placement.parent().replace(change.apply(placement.node()));
        }

        /** A signal wire into a signal node's input, or into any node's gate with {@link Wire#GATE}. */
        void signal(Node from, Node to, int input) {
            sheet.connect(new Wire(UUID.randomUUID(), from.id(), 0, to.id(), input, 0));
        }

        NetworkRuntime runtime() {
            return NetworkTicker.runtime(network.id());
        }

        /** A device node with one redstone output: the redstone the device gives out. */
        Node redstoneFrom(GlobalPos device) {
            return node(new NodeSettings.DeviceSettings(device, List.of(),
                    List.of(new NodeSettings.Output(ResourceKind.REDSTONE, List.of(), Optional.empty(), List.of(), 0, 0, 0))));
        }

        /** A device node with one redstone input: its connectors power it. */
        Node redstoneTo(GlobalPos device) {
            return node(new NodeSettings.DeviceSettings(device,
                    List.of(new NodeSettings.Input(ResourceKind.REDSTONE, List.of(), Optional.empty(), List.of())), List.of()));
        }

        /** A device node with one ME output pin (any face touching a connector). */
        Node meOut(GlobalPos device) {
            return node(new NodeSettings.DeviceSettings(device, List.of(),
                    List.of(new NodeSettings.Output(ResourceKind.ME, List.of(), Optional.empty(), List.of(), 0, 0, 0))));
        }

        /** A device node with one ME input pin. */
        Node meIn(GlobalPos device) {
            return node(new NodeSettings.DeviceSettings(device,
                    List.of(new NodeSettings.Input(ResourceKind.ME, List.of(), Optional.empty(), List.of())), List.of()));
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

    /** "In total" counts everything in the sink, not only what the cap's filter picks out (that is "matching, together"). */
    @GameTest(template = "empty")
    public static void totalCapCountsEverything(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 32));
        rig.container(1).setItem(5, new ItemStack(Items.COBBLESTONE, 60));
        // Applies to iron; at most 64 items in the sink in total: 60 cobblestone leaves room for 4 iron.
        List<NodeSettings.Limit> caps = List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, 64, "minecraft:iron_ingot"));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, caps));
        helper.runAfterDelay(30, () -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 4, "sink should take 4 iron (64 in total), has " + rig.count(1, Items.IRON_INGOT));
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

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void pulseGateRunsOneOperationPerPulse(GameTestHelper helper) {
        // A lever into a pulse gate: each time it goes on, one stack moves; held on, nothing more.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.LEVER);
        helper.setBlock(rig.devices().get(2), lever(false));
        for (int slot = 0; slot < 3; slot++) {
            rig.container(0).setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        rig.wire(source, rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.sheet().replace(source.withGate(Node.Gate.PULSE));
        rig.signal(rig.redstoneFrom(rig.device(2)), source, Wire.GATE);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "items moved before any pulse");
                    helper.setBlock(rig.devices().get(2), lever(true));
                })
                .thenExecuteAfter(30, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 64, "one pulse didn't move exactly one stack: " + rig.count(1, Items.COBBLESTONE));
                    helper.setBlock(rig.devices().get(2), lever(false));
                })
                .thenExecuteAfter(10, () -> helper.setBlock(rig.devices().get(2), lever(true)))
                .thenExecuteAfter(30, () -> check(helper, rig.count(1, Items.COBBLESTONE) == 128,
                        "the second pulse didn't move one more stack: " + rig.count(1, Items.COBBLESTONE)))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void levelSensorCountsWhatItsFilterMatches(GameTestHelper helper) {
        // A Level node counting only cobblestone in a barrel lights a lamp; dirt in the barrel doesn't count.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        rig.container(0).setItem(0, new ItemStack(Items.DIRT, 64));
        Node level = rig.node(new NodeSettings.LevelSettings(ResourceKind.ITEM, rig.device(0), List.of(), "minecraft:cobblestone"));
        rig.signal(level, rig.redstoneTo(rig.device(2)), 0);
        BlockPos lamp = rig.devices().get(2);
        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    check(helper, rig.compiled().errors().isEmpty(), "errors: " + rig.compiled().errors());
                    check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp lit for dirt the filter doesn't count");
                    rig.container(0).setItem(1, new ItemStack(Items.COBBLESTONE, 5));
                })
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp didn't light for the cobblestone"))
                .thenSucceed();
    }

    /** A Constant gives its value to a lamp; switched off it gives nothing, as a lever would. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void constantLightsALampUntilSwitchedOff(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node constant = rig.node(new NodeSettings.ConstantSettings(15));
        rig.signal(constant, rig.redstoneTo(rig.device(0)), 0);
        BlockPos lamp = rig.devices().get(0);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the constant didn't light the lamp"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(constant.id()).withEnabled(false)))
                .thenWaitUntil(() -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp stayed lit with the constant off"))
                .thenSucceed();
    }

    /**
     * A dashboard switch gives its value while on (where it stands kept in signal memory, as the editor sets it) and 0
     * while off; a Display passes on what is wired into it.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void switchThroughADisplayLightsALamp(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node toggle = rig.node(new NodeSettings.SwitchSettings(15, false, java.util.Optional.empty()));
        Node display = rig.node(new NodeSettings.DisplaySettings(NodeSettings.DisplayStyle.LIGHT, "", 15, java.util.Optional.empty(), java.util.Optional.empty(), java.util.List.of()));
        rig.signal(toggle, display, 0);
        rig.signal(display, rig.redstoneTo(rig.device(0)), 0);
        BlockPos lamp = rig.devices().get(0);
        var memory = rig.network().signalMemory();
        helper.startSequence()
                .thenExecuteAfter(5, () -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lit with the switch off"))
                .thenExecute(() -> memory.put(toggle.id().toString(), 15L))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the switch on didn't light the lamp"))
                .thenExecute(() -> memory.remove(toggle.id().toString()))
                .thenWaitUntil(() -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp stayed lit with the switch off"))
                .thenSucceed();
    }

    /** An on/off switch whose value is raised while on gives the new value at once (not the value it was flipped at). */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void raisingAnOnSwitchsValueTakesAtOnce(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node toggle = rig.node(new NodeSettings.SwitchSettings(9, false, java.util.Optional.empty()));
        rig.signal(toggle, rig.redstoneTo(rig.device(0)), 0);
        var network = rig.network();
        network.signalMemory().put(toggle.id().toString(), 9L);
        java.util.function.Supplier<Long> out = () -> io.github.leadassimilator.reticulum.engine.NetworkTicker.runtime(network.id()).signalValues()
                .getOrDefault(toggle.id(), 0L);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, out.get() == 9, "the switch on gave " + out.get()))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(toggle.id()).with(new NodeSettings.SwitchSettings(15, false, java.util.Optional.empty()))))
                .thenWaitUntil(() -> check(helper, out.get() == 15, "after raising its value to 15 it gave " + out.get()))
                .thenSucceed();
    }

    /**
     * A switched-off node gives 0 to what it is wired into, not nothing: a Constant whose "on" input it feeds stays off
     * (unwired, it would give its value).
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSwitchedOffFeederGivesZero(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node toggle = rig.node(new NodeSettings.SwitchSettings(15, false, java.util.Optional.empty()));
        Node constant = rig.node(new NodeSettings.ConstantSettings(15));
        rig.signal(toggle, constant, 0);
        rig.signal(constant, rig.redstoneTo(rig.device(0)), 0);
        rig.network().signalMemory().put(toggle.id().toString(), 15L);
        rig.sheet().replace(rig.sheet().node(toggle.id()).withEnabled(false));
        BlockPos lamp = rig.devices().get(0);
        helper.startSequence()
                .thenExecuteAfter(10, () -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lit with its switch switched off"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(toggle.id()).withEnabled(true)))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the switch back on didn't light the lamp"))
                .thenSucceed();
    }

    /** A device node gated off gives no redstone, as when switched off with its own switch: its lamp goes dark. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aGatedOffDeviceGivesNoRedstone(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node constant = rig.node(new NodeSettings.ConstantSettings(15));
        Node lampNode = rig.redstoneTo(rig.device(0));
        rig.signal(constant, lampNode, 0);
        Node toggle = rig.node(new NodeSettings.SwitchSettings(15, false, java.util.Optional.empty()));
        rig.signal(toggle, lampNode, io.github.leadassimilator.reticulum.graph.Wire.GATE);
        BlockPos lamp = rig.devices().get(0);
        var memory = rig.network().signalMemory();
        helper.startSequence()
                .thenExecuteAfter(10, () -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lit with its device gated off"))
                .thenExecute(() -> memory.put(toggle.id().toString(), 15L))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the gate on didn't light the lamp"))
                .thenExecute(() -> memory.remove(toggle.id().toString()))
                .thenWaitUntil(() -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp stayed lit with the gate off again"))
                .thenSucceed();
    }

    /**
     * A device gated off still reads its redstone: one gated by its own reading (a lever) comes back on when the lever
     * does, and what the reading feeds (a lamp) follows it.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aDeviceGatedByItsOwnReadingComesBackOn(GameTestHelper helper) {
        // A barrel between them, so the lever doesn't light the lamp itself.
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(false));
        Node reading = rig.redstoneFrom(rig.device(0));
        rig.signal(reading, reading, io.github.leadassimilator.reticulum.graph.Wire.GATE);
        rig.signal(reading, rig.redstoneTo(rig.device(2)), 0);
        BlockPos lamp = rig.devices().get(2);
        helper.startSequence()
                .thenExecuteAfter(10, () -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lit with the lever off"))
                .thenExecute(() -> helper.setBlock(rig.devices().get(0), lever(true)))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lever on didn't light the lamp: its gated-off reading stayed 0"))
                .thenSucceed();
    }

    /** A connector cut off from its controller (no antenna) stops giving redstone: its lamp goes dark. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aCutOffConnectorGivesNoRedstone(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node constant = rig.node(new NodeSettings.ConstantSettings(15));
        rig.signal(constant, rig.redstoneTo(rig.device(0)), 0);
        BlockPos lamp = rig.devices().get(0);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "setup: the lamp should light"))
                // The only cable between the controller and the connector goes.
                .thenExecute(() -> helper.setBlock(new BlockPos(2, 1, 1), Blocks.AIR))
                .thenWaitUntil(() -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the cut-off connector kept the lamp lit"))
                .thenSucceed();
    }

    /**
     * An alarm goes off once its test has passed for its delay: it is listed with its message and gives 15 (lighting a
     * lamp), and it stops when its input drops.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void alarmGoesOffAfterItsDelayAndDrivesALamp(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node level = rig.node(new NodeSettings.ConstantSettings(3));
        Node alarm = rig.node(new NodeSettings.AlarmSettings(NodeSettings.AlarmSeverity.CRITICAL, "Battery low",
                java.util.Optional.of(NodeSettings.CompareOp.LT), 5, 1));
        rig.signal(level, alarm, 0);
        rig.signal(alarm, rig.redstoneTo(rig.device(0)), 0);
        BlockPos lamp = rig.devices().get(0);
        var network = rig.network();
        java.util.function.Supplier<java.util.List<io.github.leadassimilator.reticulum.engine.Alarms.Alarm>> alarms =
                () -> io.github.leadassimilator.reticulum.engine.Alarms.of(helper.getLevel().getServer(), network).stream()
                        .filter(a -> a.node() != null).toList();
        helper.startSequence()
                .thenExecuteAfter(5, () -> check(helper, alarms.get().isEmpty(), "went off before its delay"))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "didn't light the lamp once going off"))
                .thenExecute(() -> {
                    var listed = alarms.get();
                    check(helper, listed.size() == 1 && listed.getFirst().message().equals("Battery low")
                            && listed.getFirst().severity() == NodeSettings.AlarmSeverity.CRITICAL
                            && alarm.id().equals(listed.getFirst().node()), "not listed as going off: " + listed);
                    rig.sheet().replace(rig.sheet().node(level.id()).with(new NodeSettings.ConstantSettings(9)));
                })
                .thenWaitUntil(() -> check(helper, alarms.get().isEmpty() && !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                        "kept going off with its input back above the threshold"))
                .thenSucceed();
    }

    /** A Status Beacon on a connector shows its network's worst alarm in its block state, and goes back to quiet after. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void statusBeaconShowsItsNetworksWorstAlarm(GameTestHelper helper) {
        Rig rig = rig(helper, io.github.leadassimilator.reticulum.registry.ModBlocks.STATUS_BEACON.get());
        Node level = rig.node(new NodeSettings.ConstantSettings(1));
        Node alarm = rig.node(new NodeSettings.AlarmSettings(NodeSettings.AlarmSeverity.CRITICAL, "Meltdown", java.util.Optional.empty(), 0, 0));
        rig.signal(level, alarm, 0);
        BlockPos at = rig.devices().get(0);
        var shown = io.github.leadassimilator.reticulum.block.StatusBeaconBlock.SHOWN;
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == io.github.leadassimilator.reticulum.block.StatusBeaconBlock.CRITICAL,
                        "the beacon doesn't show the critical alarm"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(level.id()).with(new NodeSettings.ConstantSettings(0))))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == 0,
                        "the beacon kept showing an alarm that stopped"))
                .thenSucceed();
    }

    /**
     * A Status Beacon only watches networks whoever placed it may view: against a private network's connector, one
     * placed by a stranger (or a machine) stays quiet; one placed by the owner shows its alarm.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void statusBeaconWatchesOnlyNetworksItsPlacerMayView(GameTestHelper helper) {
        Rig rig = rig(helper, io.github.leadassimilator.reticulum.registry.ModBlocks.STATUS_BEACON.get());
        UUID owner = UUID.randomUUID();
        rig.network().setOwner(owner, "owner");
        rig.network().setAccessMode(io.github.leadassimilator.reticulum.security.AccessMode.PRIVATE);
        Node level = rig.node(new NodeSettings.ConstantSettings(1));
        Node alarm = rig.node(new NodeSettings.AlarmSettings(NodeSettings.AlarmSeverity.CRITICAL, "Meltdown", java.util.Optional.empty(), 0, 0));
        rig.signal(level, alarm, 0);
        BlockPos at = rig.devices().get(0);
        var beacon = (io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity) helper.getBlockEntity(at);
        beacon.setPlacer(UUID.randomUUID());
        var shown = io.github.leadassimilator.reticulum.block.StatusBeaconBlock.SHOWN;
        helper.startSequence()
                .thenExecuteAfter(40, () -> check(helper, helper.getBlockState(at).getValue(shown) == 0, "a stranger's beacon shows a private network's alarm"))
                .thenExecute(() -> beacon.setPlacer(owner))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == io.github.leadassimilator.reticulum.block.StatusBeaconBlock.CRITICAL,
                        "the owner's beacon doesn't show the alarm"))
                .thenSucceed();
    }

    /** A Status Beacon in Redstone mode shows each signal level's look (by default 1 to 7 a warning's, 8 to 15 critical's); an Alarm node drives it by its severity. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void statusBeaconInRedstoneModeFollowsTheSignal(GameTestHelper helper) {
        Rig rig = rig(helper, io.github.leadassimilator.reticulum.registry.ModBlocks.STATUS_BEACON.get());
        BlockPos at = rig.devices().get(0);
        var beacon = (io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity) helper.getBlockEntity(at);
        var settings = beacon.settings();
        settings.putString("mode", "redstone");
        beacon.applySettings(settings);
        Node input = rig.node(new NodeSettings.ConstantSettings(1));
        Node alarm = rig.node(new NodeSettings.AlarmSettings(NodeSettings.AlarmSeverity.WARNING, "Low", java.util.Optional.empty(), 0, 0));
        rig.signal(input, alarm, 0);
        rig.signal(alarm, rig.redstoneTo(rig.device(0)), 0);
        var shown = io.github.leadassimilator.reticulum.block.StatusBeaconBlock.SHOWN;
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == 7 && beacon.look() != null
                        && beacon.look().color() == io.github.leadassimilator.reticulum.block.entity.BeaconLook.WARNING.color(),
                        "a warning alarm's 7 didn't show level 7 (the warning look, from level 1)"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(alarm.id()).with(new NodeSettings.AlarmSettings(NodeSettings.AlarmSeverity.CRITICAL,
                        "Low", java.util.Optional.empty(), 0, 0))))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == 15 && beacon.look() != null
                        && beacon.look().color() == io.github.leadassimilator.reticulum.block.entity.BeaconLook.CRITICAL.color(),
                        "a critical alarm's 15 didn't show level 15 (the critical look, from level 8)"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(input.id()).with(new NodeSettings.ConstantSettings(0))))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == 0,
                        "kept showing with no signal"))
                .thenSucceed();
    }

    /** A beacon in Redstone mode with a look for signal 0 shows it with no signal (opposite logic, no inverter needed). */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void statusBeaconShowsSignalZero(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 1, 2);
        helper.setBlock(at, io.github.leadassimilator.reticulum.registry.ModBlocks.STATUS_BEACON.get());
        var beacon = (io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity) helper.getBlockEntity(at);
        var settings = beacon.settings();
        settings.putString("mode", "redstone");
        var levels = settings.getCompound("levels");
        levels.put("0", new io.github.leadassimilator.reticulum.block.entity.BeaconLook(0x00FF00, io.github.leadassimilator.reticulum.block.entity.BeaconLook.BEAM,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("block.note_block.chime"), 1f, 40, 16).save());
        settings.put("levels", levels);
        beacon.applySettings(settings);
        var shown = io.github.leadassimilator.reticulum.block.StatusBeaconBlock.SHOWN;
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == io.github.leadassimilator.reticulum.block.StatusBeaconBlock.LEVEL_ZERO
                        && beacon.look() != null && beacon.look().color() == 0x00FF00, "signal 0's look isn't shown with no signal"))
                .thenExecute(() -> helper.setBlock(at.east(), Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(at).getValue(shown) == 15, "a full signal didn't show level 15"))
                .thenSucceed();
    }

    /**
     * A Sequencer steps through its tracks, each track an output of its own: two lamps take turns (held values), it
     * loops, and stopped (not looping) it gives nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sequencerStepsThroughItsTracks(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP, Blocks.REDSTONE_LAMP);
        var tracks = List.of(new NodeSettings.SequencerTrack("A", "f0"), new NodeSettings.SequencerTrack("B", "0f"));
        Node sequencer = rig.node(new NodeSettings.SequencerSettings(2, 20, true, false, tracks));
        Node toA = rig.redstoneTo(rig.device(0));
        Node toB = rig.redstoneTo(rig.device(1));
        rig.sheet().connect(new Wire(UUID.randomUUID(), sequencer.id(), 1, toA.id(), 0, 0));
        rig.sheet().connect(new Wire(UUID.randomUUID(), sequencer.id(), 2, toB.id(), 0, 0));
        BlockPos a = rig.devices().get(0);
        BlockPos b = rig.devices().get(1);
        java.util.function.BiPredicate<Boolean, Boolean> lit = (litA, litB) -> helper.getBlockState(a).getValue(RedstoneLampBlock.LIT) == litA
                && helper.getBlockState(b).getValue(RedstoneLampBlock.LIT) == litB;
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, rig.compiled().errors().isEmpty() && lit.test(true, false), "step 1 didn't light only lamp A: " + rig.compiled().errors()))
                .thenWaitUntil(() -> check(helper, lit.test(false, true), "step 2 didn't light only lamp B"))
                .thenWaitUntil(() -> check(helper, lit.test(true, false), "it didn't loop back to step 1"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(sequencer.id()).with(new NodeSettings.SequencerSettings(2, 20, false, false, tracks))))
                .thenWaitUntil(() -> check(helper, lit.test(false, false) && rig.runtime().signalValues().getOrDefault(sequencer.id(), 1L) == 0,
                        "not looping, it didn't stop after the last step"))
                .thenSucceed();
    }

    /**
     * FE a sink refused after saying it would take it (and the source wouldn't take back) goes into the controller's
     * store, up to its capacity, when power is charged for; with power free the controller takes none (it would never
     * be used). Set and put back within this call, so no other test runs under the other preset.
     */
    @GameTest(template = "empty")
    public static void lostEnergyGoesToTheController(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        var controller = (io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        var preset = io.github.leadassimilator.reticulum.ReticulumConfig.POWER_PRESET;
        var was = preset.get();
        try {
            preset.set(io.github.leadassimilator.reticulum.engine.Power.Preset.FREE);
            long none = NetworkRuntime.catchLostEnergy(helper.getLevel().getServer(), rig.network(), 500);
            check(helper, none == 0, "with power free the controller still took " + none + " FE");
            preset.set(io.github.leadassimilator.reticulum.engine.Power.Preset.BALANCED);
            long before = controller.energy();
            long caught = NetworkRuntime.catchLostEnergy(helper.getLevel().getServer(), rig.network(), 500);
            check(helper, caught == 500 && controller.energy() == before + 500, "the controller didn't catch the lost FE: " + caught);
            long more = NetworkRuntime.catchLostEnergy(helper.getLevel().getServer(), rig.network(), Integer.MAX_VALUE);
            check(helper, controller.energy() == io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity.capacity(),
                    "the controller went past its capacity: " + controller.energy() + " after " + more);
        } finally {
            preset.set(was);
        }
        helper.succeed();
    }

    /** A looping Sequencer cut shorter than the step it is on starts again instead of stopping. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void sequencerCutShortKeepsLooping(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        var tracks = List.of(new NodeSettings.SequencerTrack("", "f"));
        Node sequencer = rig.node(new NodeSettings.SequencerSettings(16, 2, true, false, tracks));
        java.util.function.LongSupplier step = () -> rig.runtime().signalValues().getOrDefault(sequencer.id(), 0L);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, step.getAsLong() >= 10, "never got to step 10: " + step.getAsLong()))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(sequencer.id()).with(new NodeSettings.SequencerSettings(4, 2, true, false, tracks))))
                .thenWaitUntil(() -> check(helper, step.getAsLong() >= 1 && step.getAsLong() <= 4, "cut to 4 steps, it shows step " + step.getAsLong()))
                .thenSucceed();
    }

    /**
     * Charts: moving iron counts in the items total and in iron's own chart (charted), not in dirt's (not charted); a
     * charted device's level is sampled each second.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chartsRecordTotalsChartedItemsAndLevels(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 64));
        var iron = new io.github.leadassimilator.reticulum.network.ChartSeries(io.github.leadassimilator.reticulum.network.ChartSeries.Kind.ITEM,
                Optional.of(net.minecraft.resources.ResourceLocation.withDefaultNamespace("iron_ingot")), Optional.empty(), Optional.empty());
        var level = new io.github.leadassimilator.reticulum.network.ChartSeries(io.github.leadassimilator.reticulum.network.ChartSeries.Kind.LEVEL,
                Optional.empty(), Optional.of(rig.device(1)), Optional.of(ResourceKind.ITEM));
        var through = new io.github.leadassimilator.reticulum.network.ChartSeries(io.github.leadassimilator.reticulum.network.ChartSeries.Kind.THROUGH,
                Optional.empty(), Optional.of(rig.device(1)), Optional.of(ResourceKind.ITEM));
        rig.network().charts().add(iron);
        rig.network().charts().add(level);
        rig.network().charts().add(through);
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        java.util.function.ToLongFunction<UUID> sum = id -> java.util.Arrays.stream(rig.runtime().metrics().history(id, false)).sum();
        java.util.function.ToLongFunction<UUID> last = id -> {
            long[] values = rig.runtime().metrics().history(id, false);
            return values[values.length - 1];
        };
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 64 && rig.count(1, Items.DIRT) == 64, "not everything moved yet");
            long total = sum.applyAsLong(io.github.leadassimilator.reticulum.network.ChartSeries.totalId(ResourceKind.ITEM));
            check(helper, total == 128, "the items total isn't 128: " + total);
            check(helper, sum.applyAsLong(iron.seriesId()) == 64, "iron's chart isn't 64: " + sum.applyAsLong(iron.seriesId()));
            UUID dirt = io.github.leadassimilator.reticulum.network.ChartSeries.idOf("item:minecraft:dirt");
            check(helper, sum.applyAsLong(dirt) == 0, "dirt was recorded without being charted");
            check(helper, last.applyAsLong(level.seriesId()) == 128, "the barrel's level isn't 128: " + last.applyAsLong(level.seriesId()));
            check(helper, sum.applyAsLong(through.seriesId(true)) == 128 && sum.applyAsLong(through.seriesId(false)) == 0,
                    "into the barrel isn't 128 (or something came out): " + sum.applyAsLong(through.seriesId(true)) + "/" + sum.applyAsLong(through.seriesId(false)));
        });
    }

    /** A Constant with its "on" input wired gives its value only while that input is above 0. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void constantFollowsItsOnInput(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.REDSTONE_LAMP);
        Node control = rig.node(new NodeSettings.ConstantSettings(0));
        Node constant = rig.node(new NodeSettings.ConstantSettings(15));
        rig.signal(control, constant, 0);
        rig.signal(constant, rig.redstoneTo(rig.device(0)), 0);
        BlockPos lamp = rig.devices().get(0);
        helper.startSequence()
                .thenExecuteAfter(20, () -> check(helper, !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lit with its on input at 0"))
                .thenExecute(() -> rig.sheet().replace(rig.sheet().node(control.id()).with(new NodeSettings.ConstantSettings(1))))
                .thenWaitUntil(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "not lit with its on input above 0"))
                .thenSucceed();
    }

    /** A Level sensor's output: the amount, 0 to 15 between two amounts, or 0 to 15 like a comparator. */
    @GameTest(template = "empty")
    public static void levelSensorMapsOntoRedstone(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 64));
        Node amount = rig.node(new NodeSettings.LevelSettings(ResourceKind.ITEM, rig.device(0), List.of(), "minecraft:cobblestone"));
        Node range = rig.node(new NodeSettings.LevelSettings(ResourceKind.ITEM, rig.device(0), List.of(), "minecraft:cobblestone",
                NodeSettings.LevelOutput.RANGE, 0, 64));
        Node comparator = rig.node(new NodeSettings.LevelSettings(ResourceKind.ITEM, rig.device(0), List.of(), "minecraft:cobblestone",
                NodeSettings.LevelOutput.COMPARATOR, 0, 0));
        helper.succeedWhen(() -> {
            var values = rig.runtime().signalValues();
            check(helper, values.getOrDefault(amount.id(), -1L) == 32, "amount: " + values.get(amount.id()));
            check(helper, values.getOrDefault(range.id(), -1L) == 8, "half of 0..64 isn't 8: " + values.get(range.id()));
            // Vanilla: 32 of 64 in one of a barrel's 27 slots gives 1; the dirt isn't counted.
            check(helper, values.getOrDefault(comparator.id(), -1L) == 1, "comparator value: " + values.get(comparator.id()));
        });
    }

    @GameTest(template = "empty")
    public static void leverLightsLampWiredDirectly(GameTestHelper helper) {
        // A device's redstone wired straight into another device, no signal node between.
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(true));
        rig.signal(rig.redstoneFrom(rig.device(0)), rig.redstoneTo(rig.device(2)), 0);
        BlockPos lamp = rig.devices().get(2);
        helper.succeedWhen(() -> check(helper, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                "lamp not lit; errors " + rig.compiled().errors()));
    }

    @GameTest(template = "empty")
    public static void leverSwitchesLampThroughNot(GameTestHelper helper) {
        // A barrel keeps the lever and the lamp apart, so only the network links them.
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(true));
        Node in = rig.redstoneFrom(rig.device(0));
        Node not = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.NOT));
        Node out = rig.redstoneTo(rig.device(2));
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

    @GameTest(template = "empty")
    public static void blocksPlacedWithoutAPlayerJoinTheNetwork(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        // A connector set by a command or a structure: no player, no placement hook.
        BlockPos connector = new BlockPos(4, 1, 1);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.BARREL);
        helper.setBlock(connector, ModBlocks.CONNECTOR.get());
        GlobalPos barrel = Devices.global(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1, 2)));
        helper.succeedWhen(() -> {
            check(helper, Topology.network(helper.getLevel(), helper.absolutePos(connector)) == rig.network(), "the connector didn't join the network");
            check(helper, rig.network().device(barrel) != null, "its barrel isn't a device");
        });
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
    public static void redstoneDirectionIsDetected(GameTestHelper helper) {
        BlockPos lever = new BlockPos(1, 1, 1);
        BlockPos lamp = new BlockPos(3, 1, 1);
        BlockPos door = new BlockPos(5, 1, 1);
        helper.setBlock(lever, lever(false));
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
        helper.setBlock(door, Blocks.OAK_DOOR);
        var level = helper.getLevel();
        check(helper, Devices.givesRedstone(level, helper.absolutePos(lever)) && !Devices.takesRedstone(level, helper.absolutePos(lever)), "lever");
        BlockPos dust = new BlockPos(1, 1, 3);
        BlockPos repeater = new BlockPos(3, 1, 3);
        // Both need something solid under them, or they pop off.
        helper.setBlock(dust.below(), Blocks.STONE);
        helper.setBlock(repeater.below(), Blocks.STONE);
        helper.setBlock(dust, Blocks.REDSTONE_WIRE);
        helper.setBlock(repeater, Blocks.REPEATER);
        check(helper, Devices.givesRedstone(level, helper.absolutePos(dust)) && Devices.takesRedstone(level, helper.absolutePos(dust)), "dust");
        check(helper, Devices.givesRedstone(level, helper.absolutePos(repeater)) && Devices.takesRedstone(level, helper.absolutePos(repeater)), "repeater");
        check(helper, !Devices.givesRedstone(level, helper.absolutePos(lamp)) && Devices.takesRedstone(level, helper.absolutePos(lamp)), "lamp");
        check(helper, !Devices.givesRedstone(level, helper.absolutePos(door)) && Devices.takesRedstone(level, helper.absolutePos(door)), "door");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gateWireSwitchesAFlow(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.LEVER);
        helper.setBlock(rig.devices().get(2), lever(false));
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        rig.wire(source, rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.signal(rig.redstoneFrom(rig.device(2)), source, Wire.GATE);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "items moved through a closed gate");
                    helper.setBlock(rig.devices().get(2), lever(true));
                })
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 64, "items didn't move once the gate opened"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void networkRunsWhileControllerIsUnpowered(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.REDSTONE_BLOCK);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        // Redstone at the controller switches the whole network.
        rig.network().switches().setRedstone(io.github.leadassimilator.reticulum.network.Switches.Redstone.LOW);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "the network ran while its controller was powered");
                    helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
                })
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 64, "the network didn't run once the controller lost power"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void counterCountsAndDelayLoopCompiles(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        Node timer = rig.node(new NodeSettings.TimerSettings(2));
        Node counter = rig.node(new NodeSettings.CounterSettings());
        Node compare = rig.node(new NodeSettings.CompareSettings(NodeSettings.CompareOp.GE, 3));
        Node lamp = rig.redstoneTo(rig.device(1));
        rig.signal(timer, counter, 0);
        rig.signal(counter, compare, 0);
        rig.signal(compare, lamp, 0);
        // A clock made of a NOT fed by its own output one tick late: a loop, but through a Delay.
        Node not = rig.node(new NodeSettings.LogicSettings(NodeSettings.LogicOp.NOT));
        Node delay = rig.node(new NodeSettings.DelaySettings(1));
        rig.signal(not, delay, 0);
        rig.signal(delay, not, 0);
        helper.succeedWhen(() -> {
            check(helper, rig.compiled().errors().isEmpty(),
                    "errors: " + rig.compiled().errors());
            check(helper, helper.getBlockState(rig.devices().get(1)).getValue(RedstoneLampBlock.LIT), "counter didn't reach 3 and light the lamp");
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
            check(helper, compiled != null && compiled.errors().stream().anyMatch(e -> e.message().contains("loop")), "loop not reported");
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
            var sides = io.github.leadassimilator.reticulum.integration.ae2.Ae2Integration.sides(helper.getLevel(), first);
            check(helper, !sides.contains(Direction.NORTH), "an ME endpoint faces a storage bus with no cable: " + sides);
            check(helper, rig.network().device(Devices.global(helper.getLevel(), first.north())) == null,
                    "a storage bus with no cable counts as a device");
            check(helper, helper.getLevel().getBlockState(first).getValue(
                    io.github.leadassimilator.reticulum.block.ConnectorBlock.LINKS.get(Direction.NORTH)) != io.github.leadassimilator.reticulum.block.Link.DEVICE,
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
            var host = appeng.api.networking.GridHelper.getNodeHost(helper.getLevel(), smart);
            var node = host == null ? null : host.getGridNode(null);
            check(helper, node != null, "no smart cable node");
            var toConnector = node.getInWorldConnections().get(Direction.SOUTH);
            int nodeUsed = node.getUsedChannels();
            int sideUsed = toConnector == null ? -1 : toConnector.getUsedChannels();
            check(helper, nodeUsed == 2 && sideUsed == 2,
                    "smart cable: node " + nodeUsed + "/" + node.getMaxChannels() + ", its connection to the connector " + sideUsed);
            // The far connector's look-at line shows the smart cable back at the controller, not its own cable's count.
            var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
            String lines = io.github.leadassimilator.reticulum.lookat.LookAt.describe(helper.getLevel(), far.south(), null, player)
                    .stream().map(net.minecraft.network.chat.Component::getString).toList().toString();
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
    public static void importedSheetsGetNewIdsAndKeepTheirWires(GameTestHelper helper) {
        Sheet sheet = new Sheet(UUID.randomUUID(), "exported");
        Node a = sheet.add(Node.of(new NodeSettings.FilterSettings("iron_ingot")));
        Node b = sheet.add(Node.of(new NodeSettings.RateLimitSettings(20)));
        sheet.connect(new Wire(UUID.randomUUID(), a.id(), Wire.REJECT, b.id(), 0, 0));
        String json = Sheet.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, sheet).getOrThrow().toString();
        Sheet pasted = Sheet.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json)).getOrThrow();
        Sheet copy = pasted.copyWithNewIds(pasted.name());
        check(helper, !copy.id().equals(sheet.id()) && copy.nodes().size() == 2 && copy.wires().size() == 1, "copy lost parts");
        check(helper, copy.nodes().stream().noneMatch(n -> n.id().equals(a.id()) || n.id().equals(b.id())), "nodes kept their old ids");
        Wire wire = copy.wires().getFirst();
        check(helper, copy.node(wire.from()) != null && copy.node(wire.to()) != null && wire.output() == Wire.REJECT, "wire not remapped onto the new nodes");
        helper.succeed();
    }

    // ---------------------------------------------------------------- orphans

    @GameTest(template = "empty")
    public static void relinkMovesAMissingDevicesNamesAndFlows(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        GlobalPos old = rig.device(0);
        GlobalPos replacement = rig.device(1);
        rig.network().device(old).setName("Crusher");
        Node node = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        // The named barrel is broken: its record stays (it has a name) and goes missing.
        helper.setBlock(rig.devices().get(0), Blocks.AIR);
        helper.succeedWhen(() -> {
            var record = rig.network().device(old);
            check(helper, record != null && record.state() != io.github.leadassimilator.reticulum.network.DeviceRecord.State.ACTIVE, "old record not missing yet");
            check(helper, NetworkManager.relinkDevice(helper.getLevel().getServer(), rig.network(), old, replacement), "relink refused");
            check(helper, "Crusher".equals(rig.network().device(replacement).name()), "name didn't move");
            check(helper, rig.network().device(old) == null, "old record still there");
            var moved = rig.sheet().node(node.id()).settings();
            check(helper, moved instanceof NodeSettings.DeviceSettings d && d.device().equals(replacement), "flow still points at the old block");
        });
    }

    @GameTest(template = "empty")
    public static void logicOpsFollowTheirTruthTables(GameTestHelper helper) {
        // {on, all} -> AND, OR, NOT, XOR, NAND, XNOR
        int[][] cases = {{0, 2}, {1, 2}, {2, 2}, {0, 1}, {1, 1}, {2, 3}};
        boolean[][] expected = {
                {false, false, true, false, true, true},
                {false, true, false, true, true, false},
                {true, true, false, false, false, true},
                {false, false, true, false, true, true},
                {true, true, false, true, false, false},
                {false, true, false, false, true, true},
        };
        NodeSettings.LogicOp[] ops = {NodeSettings.LogicOp.AND, NodeSettings.LogicOp.OR, NodeSettings.LogicOp.NOT, NodeSettings.LogicOp.XOR,
                NodeSettings.LogicOp.NAND, NodeSettings.LogicOp.XNOR};
        for (int c = 0; c < cases.length; c++) {
            for (int o = 0; o < ops.length; o++) {
                boolean result = ops[o].test(cases[c][0], cases[c][1]);
                check(helper, result == expected[c][o], ops[o] + " with " + cases[c][0] + " of " + cases[c][1] + " on gave " + result);
            }
        }
        check(helper, !NodeSettings.LogicOp.AND.test(0, 0) && NodeSettings.LogicOp.NAND.test(0, 0), "no wires: AND should be off and NAND on");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reorderingWiresChangesBranchOrder(GameTestHelper helper) {
        var sheet = new io.github.leadassimilator.reticulum.graph.Sheet(java.util.UUID.randomUUID(), "t");
        java.util.UUID splitter = java.util.UUID.randomUUID();
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            java.util.UUID wire = java.util.UUID.randomUUID();
            ids.add(wire);
            sheet.connect(new io.github.leadassimilator.reticulum.graph.Wire(wire, splitter, 0, java.util.UUID.randomUUID(), 0, i));
        }
        sheet.orderWires(java.util.List.of(ids.get(2), ids.get(0), ids.get(1)));
        java.util.Map<java.util.UUID, Integer> order = new java.util.HashMap<>();
        sheet.wires().forEach(w -> order.put(w.id(), w.order()));
        check(helper, order.get(ids.get(2)) == 0 && order.get(ids.get(0)) == 1 && order.get(ids.get(1)) == 2, "wrong order " + order);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void controllerShowsItsAntenna(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(pos, io.github.leadassimilator.reticulum.registry.ModBlocks.CONTROLLER.get().defaultBlockState(), 3);
        var controller = (io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity) helper.getLevel().getBlockEntity(pos);
        var antenna = io.github.leadassimilator.reticulum.block.ControllerBlock.ANTENNA;
        controller.setAntennaTier(2);
        check(helper, helper.getLevel().getBlockState(pos).getValue(antenna) == 2, "the controller doesn't show a tier 2 antenna");
        check(helper, helper.getLevel().getBlockEntity(pos) == controller, "changing the look replaced the controller's block entity");
        controller.setAntennaTier(0);
        check(helper, helper.getLevel().getBlockState(pos).getValue(antenna) == 0, "the mast stayed after the antenna came out");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void brokenControllerKeepsItsAntenna(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(pos, io.github.leadassimilator.reticulum.registry.ModBlocks.CONTROLLER.get().defaultBlockState(), 3);
        var controller = (io.github.leadassimilator.reticulum.block.entity.ControllerBlockEntity) helper.getLevel().getBlockEntity(pos);
        controller.setAntennaTier(2);
        helper.getLevel().destroyBlock(pos, true);
        check(helper, helper.getLevel().getBlockState(pos).isAir(), "the controller didn't break in one go");
        var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(1.5));
        check(helper, drops.size() == 1, "expected just the controller to drop, got " + drops.stream().map(e -> e.getItem().toString()).toList());
        var stack = drops.get(0).getItem();
        check(helper, stack.is(io.github.leadassimilator.reticulum.registry.ModItems.CONTROLLER.get())
                && stack.getOrDefault(io.github.leadassimilator.reticulum.registry.ModDataComponents.ANTENNA_TIER.get(), 0) == 2,
                "the dropped controller didn't keep its tier 2 antenna: " + stack);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sneakIdentifyTurnsOffTheLitFaceAcrossTheSurface(GameTestHelper helper) {
        // Two barrels side by side; the right one's west face is lit. Aiming at that shared surface hits the left
        // barrel's east face; sneak + right-click there must turn the lit face off, not light a second one.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        var level = helper.getLevel();
        BlockPos left = helper.absolutePos(rig.devices().get(0));
        BlockPos right = helper.absolutePos(rig.devices().get(1));
        var lit = io.github.leadassimilator.reticulum.network.Identifies.deviceOrFace(Devices.global(level, right), Direction.WEST);
        io.github.leadassimilator.reticulum.network.Identifies.set(level.getServer(), rig.network(), lit, true);
        // A fake player: a mock one "joins", and other mods (Jade) then try to send it packets.
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        player.setShiftKeyDown(true);
        ItemStack tool = new ItemStack(io.github.leadassimilator.reticulum.registry.ModItems.NETWORK_TOOL.get());
        tool.set(io.github.leadassimilator.reticulum.registry.ModDataComponents.TOOL_MODE.get(), io.github.leadassimilator.reticulum.item.ToolMode.IDENTIFY);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(left).add(0.5, 0, 0), Direction.EAST, left, false);
        tool.getItem().onItemUseFirst(tool, new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
        check(helper, !io.github.leadassimilator.reticulum.network.Identifies.isOn(rig.network(), lit), "the lit face across the surface stayed on");
        var wrong = io.github.leadassimilator.reticulum.network.Identifies.deviceOrFace(Devices.global(level, left), Direction.EAST);
        check(helper, !io.github.leadassimilator.reticulum.network.Identifies.isOn(rig.network(), wrong), "the neighboring face was lit instead");
        helper.succeed();
    }

    // ---------------------------------------------------------------- saving

    @GameTest(template = "empty")
    public static void networksLoadedWithoutSettingsDontShareThem(GameTestHelper helper) {
        // Two networks saved with no switches or overflow of their own (all defaults), then loaded.
        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        java.util.function.Function<String, io.github.leadassimilator.reticulum.network.Network> load = name -> {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("id", java.util.UUID.randomUUID().toString());
            tag.putString("name", name);
            return io.github.leadassimilator.reticulum.network.Network.CODEC.parse(ops, tag).getOrThrow();
        };
        var a = load.apply("A");
        var b = load.apply("B");
        a.setEnabled(false);
        check(helper, b.enabled(), "switching network A off switched B off too");
        check(helper, a.switches() != b.switches(), "the two networks share one switches object");
        check(helper, a.overflow() != b.overflow(), "the two networks share one overflow buffer");
        helper.succeed();
    }

    // ---------------------------------------------------------------- editor

    @GameTest(template = "empty")
    public static void deviceContentsListsWhatABlockHolds(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) helper.getLevel().getBlockEntity(pos);
        barrel.setItem(0, new ItemStack(Items.COBBLESTONE, 40));
        barrel.setItem(1, new ItemStack(Items.COBBLESTONE, 24));
        barrel.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        var entries = io.github.leadassimilator.reticulum.protocol.web.DeviceContents.read(helper.getLevel(), pos, "item").getAsJsonArray("entries");
        java.util.Map<String, Long> found = new java.util.HashMap<>();
        entries.forEach(e -> found.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("amount").getAsLong()));
        // Every side shows the same inventory; each item still counts once.
        check(helper, found.equals(java.util.Map.of("minecraft:cobblestone", 64L, "minecraft:iron_ingot", 3L)), "wrong contents " + found);
        helper.succeed();
    }

    // ---------------------------------------------------------------- facades

    @GameTest(template = "empty")
    public static void facadesPaintAWholeStackWithAnyFullBlock(GameTestHelper helper) {
        ItemStack stack = new ItemStack(io.github.leadassimilator.reticulum.registry.ModItems.FACADE.get(), 64);
        check(helper, io.github.leadassimilator.reticulum.item.FacadeItem.state(stack) == null, "a new facade isn't blank");
        check(helper, io.github.leadassimilator.reticulum.item.FacadeItem.paint(stack, Blocks.STONE_BRICKS.defaultBlockState()), "stone bricks were refused");
        check(helper, stack.getCount() == 64 && io.github.leadassimilator.reticulum.item.FacadeItem.state(stack) == Blocks.STONE_BRICKS.defaultBlockState(),
                "the stack wasn't painted: " + stack);
        var log = Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X);
        check(helper, io.github.leadassimilator.reticulum.item.FacadeItem.paint(stack, log) && io.github.leadassimilator.reticulum.item.FacadeItem.state(stack) == log,
                "a sideways log wasn't kept as it is");
        check(helper, !io.github.leadassimilator.reticulum.item.FacadeItem.paint(stack, Blocks.TORCH.defaultBlockState()), "a torch (not a full block) was accepted");
        check(helper, !io.github.leadassimilator.reticulum.item.FacadeItem.paint(stack, Blocks.CHEST.defaultBlockState()), "a chest (block entity) was accepted");
        check(helper, io.github.leadassimilator.reticulum.item.FacadeItem.state(stack) == log, "a refused block changed the stack");
        check(helper, io.github.leadassimilator.reticulum.item.FacadeItem.paint(stack, null) && io.github.leadassimilator.reticulum.item.FacadeItem.state(stack) == null,
                "the stack wasn't made blank again");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void facadedConnectorStillReachesItsDevice(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        BlockPos connector = helper.absolutePos(new BlockPos(3, 1, 1));
        var be = (io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity) helper.getLevel().getBlockEntity(connector);
        be.setFacade(Blocks.STONE_BRICKS.defaultBlockState());
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        var state = helper.getLevel().getBlockState(connector);
        check(helper, state.getSoundType(helper.getLevel(), connector, null) == Blocks.STONE_BRICKS.defaultBlockState().getSoundType(),
                "the facade doesn't sound like stone bricks");
        check(helper, state.getBlock().hidesNeighborFace(helper.getLevel(), connector, state, Blocks.STONE.defaultBlockState(), Direction.UP),
                "a stone brick facade doesn't hide the face next to it");
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 16, "items didn't move through a facaded connector");
            // Breaking the connector drops its facade.
            helper.getLevel().destroyBlock(connector, false);
            var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(connector).inflate(1.5));
            check(helper, drops.stream().anyMatch(e -> io.github.leadassimilator.reticulum.item.FacadeItem.state(e.getItem()) == Blocks.STONE_BRICKS.defaultBlockState()),
                    "no facade dropped when the connector broke");
        });
    }

    // ---------------------------------------------------------------- power

    /** Switches the whole server to the balanced power preset, so it runs in a batch of its own and switches it back. */
    @GameTest(template = "empty", batch = "power", timeoutTicks = 200)
    public static void networksNeedPowerWhenTheServerChargesForIt(GameTestHelper helper) {
        var preset = io.github.leadassimilator.reticulum.ReticulumConfig.POWER_PRESET;
        preset.set(io.github.leadassimilator.reticulum.engine.Power.Preset.BALANCED);
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        ControllerBlockEntity controller = (ControllerBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        long[] charged = new long[1];
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    check(helper, rig.count(1, Items.COBBLESTONE) == 0, "items moved without power");
                    check(helper, !rig.runtime().powered(), "network says it has power with an empty controller");
                    controller.energyStorage().receiveEnergy(100_000, false);
                    charged[0] = controller.energy();
                })
                .thenWaitUntil(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 64, "items didn't move once the controller had FE"))
                .thenExecute(() -> {
                    check(helper, rig.runtime().powered(), "network still unpowered");
                    check(helper, controller.energy() < charged[0], "running cost nothing");
                    preset.set(io.github.leadassimilator.reticulum.engine.Power.Preset.FREE);
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- identify

    @GameTest(template = "empty")
    public static void identifyLightsDevicesAndFacesForViewers(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL);
        var server = helper.getLevel().getServer();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        GlobalPos barrel = rig.device(0);
        rig.network().device(barrel).setFaceName(io.github.leadassimilator.reticulum.network.FaceKey.NORTH, "Intake");
        var device = io.github.leadassimilator.reticulum.network.Identify.device(barrel, 0);
        var face = io.github.leadassimilator.reticulum.network.Identify.face(barrel, io.github.leadassimilator.reticulum.network.FaceKey.NORTH, 0);
        check(helper, io.github.leadassimilator.reticulum.network.Identifies.toggle(server, rig.network(), device), "device didn't light up");
        check(helper, io.github.leadassimilator.reticulum.network.Identifies.toggle(server, rig.network(), face), "face didn't light up");
        var markers = io.github.leadassimilator.reticulum.network.Identifies.markersFor(player);
        check(helper, markers.stream().anyMatch(m -> m.pos().equals(barrel) && m.face().isEmpty()), "no marker for the device: " + markers);
        check(helper, markers.stream().anyMatch(m -> m.pos().equals(barrel) && m.face().equals(Optional.of(Direction.NORTH)) && m.label().endsWith("› Intake")),
                "no named marker for the face: " + markers);
        // Only this barrel's markers: other tests light things up alongside.
        check(helper, markers.stream().filter(m -> m.pos().equals(barrel)).map(m -> m.color()).distinct().count() == 2, "the two identifies share a color");
        check(helper, !io.github.leadassimilator.reticulum.network.Identifies.toggle(server, rig.network(), device), "device didn't turn off");
        io.github.leadassimilator.reticulum.network.Identifies.clear(server, rig.network());
        check(helper, io.github.leadassimilator.reticulum.network.Identifies.markersFor(player).stream().noneMatch(m -> m.pos().equals(barrel)),
                "still lit after clearing");
        helper.succeed();
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
    public static void sheetsLeaveOutNodesThatNoLongerLoad(GameTestHelper helper) {
        String json = "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"old\",\"nodes\":["
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"settings\":{\"kind\":\"me_port\"}},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"settings\":{\"kind\":\"filter\",\"expression\":\"\"}}]}";
        Sheet sheet = Sheet.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json)).getOrThrow();
        check(helper, sheet.nodes().size() == 1 && sheet.nodes().getFirst().kind() == NodeSettings.Kind.FILTER,
                "expected only the filter to load, got " + sheet.nodes());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disabledSheetMovesNothing(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.sheetNode();
        rig.network().setSheetEnabled(rig.sheet(), false);
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

    @GameTest(template = "empty")
    public static void weightedSplitterSharesByWeight(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 1);
        UUID toFirst = UUID.randomUUID();
        UUID toSecond = UUID.randomUUID();
        Node splitter = rig.node(new NodeSettings.SplitterSettings(NodeSettings.SplitMode.WEIGHTED, java.util.Map.of(toFirst, 3, toSecond, 1)));
        rig.wire(source, splitter);
        rig.sheet().connect(new Wire(toFirst, splitter.id(), 0, rig.sink(1, ResourceKind.ITEM, List.of()).id(), 0, 0));
        rig.sheet().connect(new Wire(toSecond, splitter.id(), 0, rig.sink(2, ResourceKind.ITEM, List.of()).id(), 0, 1));
        helper.succeedWhen(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 6 && rig.count(2, Items.COBBLESTONE) == 2,
                "expected 6/2 for weights 3:1, got " + rig.count(1, Items.COBBLESTONE) + "/" + rig.count(2, Items.COBBLESTONE)));
    }

    @GameTest(template = "empty")
    public static void nearestSplitterFillsTheNearestFirst(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 16);
        Node splitter = rig.node(new NodeSettings.SplitterSettings(NodeSettings.SplitMode.NEAREST));
        rig.wire(source, splitter);
        // The far barrel is wired first; nearest-first must still fill the near one.
        rig.sheet().connect(new Wire(UUID.randomUUID(), splitter.id(), 0, rig.sink(3, ResourceKind.ITEM, List.of()).id(), 0, 0));
        rig.sheet().connect(new Wire(UUID.randomUUID(), splitter.id(), 0, rig.sink(1, ResourceKind.ITEM, List.of()).id(), 0, 1));
        helper.succeedWhen(() -> check(helper, rig.count(1, Items.COBBLESTONE) == 16 && rig.count(3, Items.COBBLESTONE) == 0,
                "expected all 16 in the near barrel, got near " + rig.count(1, Items.COBBLESTONE) + ", far " + rig.count(3, Items.COBBLESTONE)));
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
    public static void kitHonorsATotalKeepRule(GameTestHelper helper) {
        // Keep 10 items in total: a kit of 2 iron + 2 gold from 6 + 6 would leave 8, so it mustn't move.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 6));
        rig.container(0).setItem(1, new ItemStack(Items.GOLD_INGOT, 6));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, 10, "")), 64);
        Node kit = rig.node(new NodeSettings.KitSettings(List.of(
                new NodeSettings.KitLine("id:minecraft:iron_ingot", 2),
                new NodeSettings.KitLine("id:minecraft:gold_ingot", 2)), 1));
        rig.wire(source, kit);
        rig.wire(kit, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(0, Items.IRON_INGOT) + rig.count(0, Items.GOLD_INGOT) == 12,
                    "the kit took items below the keep amount: " + rig.count(1, Items.IRON_INGOT) + " iron, " + rig.count(1, Items.GOLD_INGOT) + " gold");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void emptyKitMovesNothing(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 6));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 64);
        Node kit = rig.node(new NodeSettings.KitSettings(List.of(), 64));
        rig.wire(source, kit);
        rig.wire(kit, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(0, Items.IRON_INGOT) == 6, "an empty kit moved items");
            check(helper, rig.runtime().backoff(kit.id()) > 1, "an empty kit counts as having moved, so it never backs off");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void hugeSourceIntervalDoesntStopTheNetwork(GameTestHelper helper) {
        // interval x back-off used to wrap around to 0: a division by zero that aborted the whole network's tick.
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        // An empty source that idles: its back-off doubles to 8, and 536870912 x 8 wraps to 0 in int arithmetic.
        rig.wire(rig.output(0, new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 64, 536_870_912, 0)),
                rig.sink(1, ResourceKind.ITEM, List.of()));
        rig.container(2).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        rig.wire(rig.source(2, ResourceKind.ITEM, List.of(), 64), rig.sink(3, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> check(helper, rig.count(3, Items.COBBLESTONE) == 10, "the other flow stopped"));
    }

    @GameTest(template = "empty")
    public static void redstoneGoesDarkWhenItsDeviceIsGone(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(true));
        rig.signal(rig.redstoneFrom(rig.device(0)), rig.redstoneTo(rig.device(2)), 0);
        BlockPos connector = new BlockPos(5, 1, 1);
        var be = (io.github.leadassimilator.reticulum.block.entity.ConnectorBlockEntity) helper.getBlockEntity(connector);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, be.power(Direction.SOUTH) > 0, "connector not powering the lamp"))
                .thenExecute(() -> helper.setBlock(rig.devices().get(2), Blocks.AIR))
                .thenWaitUntil(() -> check(helper, be.power(Direction.SOUTH) == 0, "the connector still powers where the lamp was"))
                .thenSucceed();
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

    /** Slot-range tests with the server's fallback through faces on: their own batch, which turns it on and off. */
    private static final String SLOT_FACES = "slot_faces";

    @net.minecraft.gametest.framework.BeforeBatch(batch = SLOT_FACES)
    public static void slotRangesThroughFacesOn(net.minecraft.server.level.ServerLevel level) {
        io.github.leadassimilator.reticulum.ReticulumConfig.SLOT_RANGES_THROUGH_FACES.set(true);
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = SLOT_FACES)
    public static void slotRangesThroughFacesOff(net.minecraft.server.level.ServerLevel level) {
        io.github.leadassimilator.reticulum.ReticulumConfig.SLOT_RANGES_THROUGH_FACES.set(false);
    }

    /**
     * With the fallback on, a slot range whose slots are full doesn't spill through the faces into slots outside it: a
     * furnace taking only into its input slot (full of something else) gets no coal in its fuel slot.
     */
    @GameTest(template = "empty", batch = SLOT_FACES, timeoutTicks = 60)
    public static void aFullSlotRangeDoesntSpillIntoOtherSlots(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.FURNACE);
        rig.container(0).setItem(0, new ItemStack(Items.COAL, 8));
        rig.container(1).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Node sink = rig.node(new NodeSettings.DeviceSettings(rig.device(1), List.of(
                new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.of(new NodeSettings.SlotRange(0, 0)), List.of())), List.of()));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 0), sink);
        helper.runAfterDelay(40, () -> {
            check(helper, rig.container(1).getItem(1).isEmpty() && rig.count(0, Items.COAL) == 8, "coal went past the slot range");
            helper.succeed();
        });
    }

    /** By default a slot range on a Mekanism machine with faces left to work out moves nothing: faces need picking. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aSlotRangeOnAMekanismMachineNeedsFaces(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        Rig rig = mekanismSlotRange(helper);
        helper.runAfterDelay(20, () -> {
            check(helper, rig.count(0, Items.IRON_ORE) == 8, "items went in with the fallback off");
            helper.succeed();
        });
    }

    /** With the fallback on, a slot range on a Mekanism machine moves items in through its faces. */
    @GameTest(template = "empty", batch = SLOT_FACES, timeoutTicks = 100)
    public static void aSlotRangeOnAMekanismMachineGoesThroughItsFaces(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        Rig rig = mekanismSlotRange(helper);
        helper.succeedWhen(() -> check(helper, rig.count(0, Items.IRON_ORE) < 8, "nothing went in with the fallback on"));
    }

    /** A barrel of iron ore wired into an enrichment chamber, slots 0 to 20, faces left to work out. */
    private static Rig mekanismSlotRange(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, MekanismTestSupport.block("enrichment_chamber"));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        rig.container(0).setItem(0, new ItemStack(Items.IRON_ORE, 8));
        Node sink = rig.node(new NodeSettings.DeviceSettings(rig.device(1), List.of(
                new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.of(new NodeSettings.SlotRange(0, 20)), List.of())), List.of()));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 0), sink);
        return rig;
    }

    /** With the fallback on, a full block with a slot range is just full: nothing goes through its faces past the range. */
    @GameTest(template = "empty", batch = SLOT_FACES, timeoutTicks = 60)
    public static void aFullBlockWithASlotRangeIsJustFull(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.CHEST);
        rig.container(0).setItem(0, new ItemStack(Items.COAL, 8));
        for (int slot = 0; slot < rig.container(1).getContainerSize(); slot++) {
            rig.container(1).setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        Node sink = rig.node(new NodeSettings.DeviceSettings(rig.device(1), List.of(
                new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.of(new NodeSettings.SlotRange(0, 3)), List.of())), List.of()));
        Node source = rig.source(0, ResourceKind.ITEM, List.of(), 0);
        rig.wire(source, sink);
        helper.runAfterDelay(20, () -> {
            check(helper, status(rig, source) == io.github.leadassimilator.reticulum.engine.Status.TARGET_FULL && rig.count(0, Items.COAL) == 8,
                    "a full chest says " + status(rig, source));
            helper.succeed();
        });
    }

    /**
     * A glowstone facade lights the world as the server sees it: around it right away, and as a light source when its chunk
     * is loaded again (found among the chunk's light sources).
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aGlowingFacadeLightsTheServersWorld(GameTestHelper helper) {
        place(helper, new BlockPos(2, 1, 2), ModBlocks.CABLE.get(), null);
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        var be = (io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity) helper.getLevel().getBlockEntity(at);
        be.setFacade(Blocks.GLOWSTONE.defaultBlockState());
        helper.runAfterDelay(20, () -> {
            int light = helper.getLevel().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at.above());
            check(helper, light >= 13, "beside the glowstone facade the block light is " + light);
            boolean[] source = {false};
            helper.getLevel().getChunkAt(at).findBlockLightSources((pos, state) -> source[0] |= pos.equals(at));
            check(helper, source[0], "the facade isn't among its chunk's light sources (dark after a reload)");
            helper.succeed();
        });
    }

    /** Filters can't stall the server: a glob full of stars and a backtracking regex both answer at once. */
    @GameTest(template = "empty")
    public static void filtersCantStallTheServer(GameTestHelper helper) throws Exception {
        long start = System.nanoTime();
        String id = "minecraft:" + "a".repeat(40);
        boolean glob = io.github.leadassimilator.reticulum.filter.Filters.idMatcher("********************q").test(id);
        boolean globHit = io.github.leadassimilator.reticulum.filter.Filters.idMatcher("*:a*a?a").test(id);
        boolean regex = io.github.leadassimilator.reticulum.filter.Filters.textMatcher(
                new io.github.leadassimilator.reticulum.filter.FilterExpr.Atom("name", "^(a|aa)+$", true)).test("a".repeat(49) + "b");
        long ms = (System.nanoTime() - start) / 1_000_000;
        check(helper, !glob && globHit && !regex, "wrong answers: " + glob + " " + globHit + " " + regex);
        check(helper, ms < 1000, "filters took " + ms + " ms");
        helper.succeed();
    }

    /** FE stored in a block, read once (through its unsided handler, or the first face that answers). */
    private static long energyStored(GameTestHelper helper, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        var storage = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, null);
        for (Direction side : Direction.values()) {
            if (storage != null) {
                break;
            }
            storage = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
        }
        return storage == null ? -1 : storage.getEnergyStored();
    }

    /** Puts FE into a block through every face that takes it, again and again (machines limit each insert). */
    private static void fillEnergy(GameTestHelper helper, BlockPos rel, long amount) {
        BlockPos pos = helper.absolutePos(rel);
        long left = amount;
        for (int round = 0; round < 10_000 && left > 0; round++) {
            boolean any = false;
            for (Direction side : Direction.values()) {
                var storage = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
                if (storage != null && left > 0) {
                    int got = storage.receiveEnergy((int) Math.min(left, Integer.MAX_VALUE), false);
                    left -= got;
                    any |= got > 0;
                }
            }
            if (!any) {
                break;
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void energyCapCountsAMachineOnce(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        // A Mekanism energy cube answers on every face with its own wrapper around the one store; a cap must count
        // that store once, not once per face.
        Block cube = MekanismTestSupport.block("basic_energy_cube");
        Rig rig = rig(helper, cube, cube);
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(0)));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        fillEnergy(helper, rig.devices().get(0), 500_000);
        long start = energyStored(helper, rig.devices().get(0));
        check(helper, start > 100_000, "could only fill the source cube with " + start + " FE");
        rig.wire(rig.source(0, ResourceKind.ENERGY, List.of(), 0),
                rig.sink(1, ResourceKind.ENERGY, List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, 20_000, ""))));
        helper.startSequence()
                .thenExecuteAfter(200, () -> {
                    long filled = energyStored(helper, rig.devices().get(1));
                    long left = energyStored(helper, rig.devices().get(0));
                    check(helper, filled >= 19_000 && filled <= 22_000, "capped at 20000 FE, the target holds " + filled);
                    // Nothing is lost on the way (a little rounding between Mekanism's joules and FE aside).
                    check(helper, Math.abs(start - left - filled) <= 50, "moved " + (start - left) + " FE but " + filled + " arrived");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void energyKeepCountsAMachineOnce(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        Block cube = MekanismTestSupport.block("basic_energy_cube");
        Rig rig = rig(helper, cube, cube);
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(0)));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        fillEnergy(helper, rig.devices().get(0), 500_000);
        long start = energyStored(helper, rig.devices().get(0));
        long keep = start - 20_000;
        rig.wire(rig.source(0, ResourceKind.ENERGY, List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, keep, "")), 0),
                rig.sink(1, ResourceKind.ENERGY, List.of()));
        helper.startSequence()
                .thenExecuteAfter(200, () -> {
                    long left = energyStored(helper, rig.devices().get(0));
                    check(helper, left >= keep - 50, "keeping " + keep + " FE, the source fell to " + left);
                    check(helper, energyStored(helper, rig.devices().get(1)) >= 19_000, "the 20000 FE above the keep amount didn't move");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void twoRoutesIntoOneMachineDontOverdraw(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        // A splitter feeding two inputs of the same nearly full cube: together they may ask only for its real room.
        Block cube = MekanismTestSupport.block("basic_energy_cube");
        Rig rig = rig(helper, cube, cube);
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(0)));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        fillEnergy(helper, rig.devices().get(0), 500_000);
        fillEnergy(helper, rig.devices().get(1), 2_000_000);
        long sourceStart = energyStored(helper, rig.devices().get(0));
        long targetStart = energyStored(helper, rig.devices().get(1));
        Node source = rig.source(0, ResourceKind.ENERGY, List.of(), 0);
        Node splitter = rig.node(new NodeSettings.SplitterSettings(NodeSettings.SplitMode.ROUND_ROBIN));
        GlobalPos target = Devices.global(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        var input = new NodeSettings.Input(ResourceKind.ENERGY, List.of(), Optional.empty(), List.of());
        Node sink = rig.node(new NodeSettings.DeviceSettings(target, List.of(input, input), List.of()));
        rig.wire(source, splitter);
        rig.sheet().connect(new Wire(UUID.randomUUID(), splitter.id(), 0, sink.id(), 0));
        rig.sheet().connect(new Wire(UUID.randomUUID(), splitter.id(), 0, sink.id(), 1));
        helper.startSequence()
                .thenExecuteAfter(200, () -> {
                    long sourceLeft = energyStored(helper, rig.devices().get(0));
                    long targetNow = energyStored(helper, rig.devices().get(1));
                    check(helper, Math.abs((sourceStart - sourceLeft) - (targetNow - targetStart)) <= 50,
                            "the source gave " + (sourceStart - sourceLeft) + " FE but the target gained " + (targetNow - targetStart));
                })
                .thenSucceed();
    }

    /** mB of fluid a block holds, counted once (its unsided handler, or the first face that answers). */
    private static long fluidStored(GameTestHelper helper, BlockPos rel) {
        BlockPos pos = helper.absolutePos(rel);
        var handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null);
        for (Direction side : Direction.values()) {
            if (handler != null) {
                break;
            }
            handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, side);
        }
        long total = 0;
        for (int tank = 0; handler != null && tank < handler.getTanks(); tank++) {
            total += handler.getFluidInTank(tank).getAmount();
        }
        return total;
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void fluidCapStopsAtTheCap(GameTestHelper helper) {
        if (!Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        Block tank = MekanismTestSupport.block("basic_fluid_tank");
        Rig rig = rig(helper, tank, tank);
        BlockPos from = helper.absolutePos(rig.devices().get(0));
        MekanismTestSupport.placedByPlayer(helper.getLevel(), from);
        MekanismTestSupport.placedByPlayer(helper.getLevel(), helper.absolutePos(rig.devices().get(1)));
        // Mekanism's unsided handler is read-only: fill through the faces.
        int left = 10_000;
        for (Direction side : Direction.values()) {
            var handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, from, side);
            if (handler != null && left > 0) {
                left -= handler.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, left),
                        net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            }
        }
        check(helper, fluidStored(helper, rig.devices().get(0)) == 10_000, "couldn't fill the source tank");
        rig.wire(rig.source(0, ResourceKind.FLUID, List.of(), 0),
                rig.sink(1, ResourceKind.FLUID, List.of(new NodeSettings.Limit(NodeSettings.Scope.TOTAL, 3000, ""))));
        helper.startSequence()
                .thenExecuteAfter(200, () -> {
                    check(helper, fluidStored(helper, rig.devices().get(1)) == 3000, "capped at 3000 mB, the target holds " + fluidStored(helper, rig.devices().get(1)));
                    check(helper, fluidStored(helper, rig.devices().get(0)) == 7000, "the source holds " + fluidStored(helper, rig.devices().get(0)));
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void chemicalKeepLeavesTheRest(GameTestHelper helper) {
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
        check(helper, MekanismTestSupport.fill(helper.getLevel(), from, "hydrogen", 4000) == 4000, "couldn't fill the source tank");
        rig.wire(rig.source(0, ResourceKind.CHEMICAL, List.of(new NodeSettings.Limit(NodeSettings.Scope.PER_TYPE, 1000, "")), 0),
                rig.sink(1, ResourceKind.CHEMICAL, List.of()));
        helper.startSequence()
                .thenExecuteAfter(200, () -> {
                    check(helper, MekanismTestSupport.stored(helper.getLevel(), from) == 1000, "keeping 1000 mB, the source holds " + MekanismTestSupport.stored(helper.getLevel(), from));
                    check(helper, MekanismTestSupport.stored(helper.getLevel(), to) == 3000, "the target holds " + MekanismTestSupport.stored(helper.getLevel(), to));
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void evenShareHandsOutRemainder(GameTestHelper helper) {
        long[] share = shareFor(10, new long[] {100, 100, 100}, false);
        check(helper, share[0] + share[1] + share[2] == 10 && share[0] == 4 && share[1] == 3 && share[2] == 3, "uneven split " + java.util.Arrays.toString(share));
        share = shareFor(100, new long[] {10, 100, 100}, false);
        check(helper, share[0] == 10 && share[1] == 45 && share[2] == 45, "unused share not passed on " + java.util.Arrays.toString(share));
        share = shareFor(100, new long[] {60, 60}, true);
        check(helper, share[0] == 60 && share[1] == 40, "priority fill wrong " + java.util.Arrays.toString(share));
        share = weightedShareFor(100, new long[] {100, 100}, new long[] {3, 1});
        check(helper, share[0] == 75 && share[1] == 25, "weighted split wrong " + java.util.Arrays.toString(share));
        share = weightedShareFor(100, new long[] {10, 100}, new long[] {3, 1});
        check(helper, share[0] == 10 && share[1] == 90, "weighted split didn't pass the unused share on " + java.util.Arrays.toString(share));
        helper.succeed();
    }

    private static long[] weightedShareFor(long supply, long[] demand, long[] weights) {
        try {
            var method = Class.forName("io.github.leadassimilator.reticulum.engine.ContinuousMover")
                    .getDeclaredMethod("share", long.class, long[].class, boolean.class, long[].class);
            method.setAccessible(true);
            return (long[]) method.invoke(null, supply, demand, false, weights);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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

    /** A blueprint holding "input -> filter (ingots) -> output", used between a barrel and another barrel. */
    @GameTest(template = "empty")
    public static void blueprintInstanceIsPastedIn(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 5));

        Sheet template = new Sheet(UUID.randomUUID(), "only ingots");
        template.setBlueprint(true);
        Node in = template.add(Node.of(new NodeSettings.SheetPinSettings(true, "in")));
        Node filter = template.add(Node.of(new NodeSettings.FilterSettings("tag:c:ingots")));
        Node out = template.add(Node.of(new NodeSettings.SheetPinSettings(false, "out")));
        template.connect(Wire.of(in, filter));
        template.connect(Wire.of(filter, out));
        rig.network().sheets().add(template);

        Node sub = rig.node(new NodeSettings.InstanceSettings(template.id()));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), sub);
        rig.wire(sub, rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.succeedWhen(() -> {
            check(helper, rig.count(1, Items.IRON_INGOT) == 5, "ingots did not pass through the blueprint");
            check(helper, rig.count(1, Items.DIRT) == 0 && rig.count(0, Items.DIRT) == 5, "the blueprint's filter was not applied");
        });
    }

    /** A blueprint "slot A -> slot B" laid out on barrels 0 and 1; returns it with its two slots. */
    private static Node[] barrelBlueprint(Rig rig, Sheet blueprint) {
        blueprint.setBlueprint(true);
        Node a = blueprint.add(Node.of(new NodeSettings.DeviceSettings(rig.device(0), List.of(),
                List.of(new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 64, 1, 0)))));
        Node b = blueprint.add(Node.of(new NodeSettings.DeviceSettings(rig.device(1),
                List.of(new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.empty(), List.of())), List.of())));
        blueprint.connect(Wire.of(a, b));
        rig.network().sheets().add(blueprint);
        return new Node[] {a, b};
    }

    /**
     * A blueprint placed inside another is filled by each placement of the outer one: two placements of B (holding a
     * placed A, "slot a -> slot b") move between their own blocks. Blocks the inner node holds itself (it is part of B's
     * template) are not used.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nestedSlotsAreFilledByEachOuterPlacement(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.DIRT, 5));
        rig.container(2).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        rig.container(4).setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        Sheet a = new Sheet(UUID.randomUUID(), "pair");
        Node[] slots = barrelBlueprint(rig, a);
        Sheet b = new Sheet(UUID.randomUUID(), "outer");
        b.setBlueprint(true);
        // Holding blocks of its own, as a blueprint's node shouldn't: ignored.
        Node inner = b.add(Node.of(new NodeSettings.InstanceSettings(a.id(), Map.of(
                slots[0].id(), new NodeSettings.Binding(rig.device(0), 0), slots[1].id(), new NodeSettings.Binding(rig.device(1), 0)))));
        rig.network().sheets().add(b);
        java.util.function.BiFunction<Integer, Integer, Map<UUID, NodeSettings.Binding>> filled = (from, to) -> Map.of(
                io.github.leadassimilator.reticulum.graph.Blueprints.nested(inner.id(), slots[0].id()), new NodeSettings.Binding(rig.device(from), 0),
                io.github.leadassimilator.reticulum.graph.Blueprints.nested(inner.id(), slots[1].id()), new NodeSettings.Binding(rig.device(to), 0));
        rig.node(new NodeSettings.InstanceSettings(b.id(), filled.apply(2, 3)));
        rig.node(new NodeSettings.InstanceSettings(b.id(), filled.apply(4, 5)));
        check(helper, io.github.leadassimilator.reticulum.graph.Blueprints.slots(rig.network(), b).size() == 2, "B doesn't count A's two slots as its own");
        helper.succeedWhen(() -> {
            check(helper, rig.count(3, Items.IRON_INGOT) == 5, "the first placement didn't move between its own blocks");
            check(helper, rig.count(5, Items.GOLD_INGOT) == 5, "the second placement didn't move between its own blocks");
            check(helper, rig.count(0, Items.DIRT) == 5, "blocks held inside the blueprint were used");
        });
    }

    /** An instance moves between the blocks filling its slots, not the ones the blueprint was laid out on. */
    @GameTest(template = "empty")
    public static void instanceMovesBetweenTheBlocksFillingItsSlots(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.DIRT, 5));
        rig.container(2).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        Sheet blueprint = new Sheet(UUID.randomUUID(), "pair");
        Node[] slots = barrelBlueprint(rig, blueprint);
        rig.node(new NodeSettings.InstanceSettings(blueprint.id(), Map.of(
                slots[0].id(), new NodeSettings.Binding(rig.device(2), 0),
                slots[1].id(), new NodeSettings.Binding(rig.device(3), 0))));
        check(helper, NodeSettings.Binding.turn(FaceKey.NORTH, 1) == FaceKey.EAST && NodeSettings.Binding.turn(FaceKey.UP, 1) == FaceKey.UP
                && NodeSettings.Binding.turn(FaceKey.WEST, -1) == FaceKey.SOUTH, "faces don't turn right");
        helper.succeedWhen(() -> {
            check(helper, rig.count(3, Items.IRON_INGOT) == 5, "the instance didn't move between its filled slots");
            check(helper, rig.count(0, Items.DIRT) == 5, "the instance moved from the block the blueprint was laid out on");
        });
    }

    @GameTest(template = "empty")
    public static void instanceWithAnEmptySlotIsReported(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL, Blocks.BARREL);
        rig.container(2).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        Sheet blueprint = new Sheet(UUID.randomUUID(), "pair");
        Node[] slots = barrelBlueprint(rig, blueprint);
        rig.node(new NodeSettings.InstanceSettings(blueprint.id(), Map.of(slots[0].id(), new NodeSettings.Binding(rig.device(2), 0))));
        helper.startSequence()
                .thenExecuteAfter(30, () -> {
                    check(helper, rig.compiled().errors().stream().anyMatch(e -> e.message().contains("has no block yet")),
                            "the empty slot wasn't reported: " + rig.compiled().errors());
                    check(helper, rig.count(2, Items.IRON_INGOT) == 5, "items moved through an empty slot");
                })
                .thenSucceed();
    }

    /** Puts the test sheet inside a new sheet, "smelting", placed in the root; returns smelting. */
    private static Sheet nestInSmelting(Rig rig) {
        Node node = rig.sheetNode();
        Sheet smelting = new Sheet(UUID.randomUUID(), "smelting");
        rig.network().sheets().add(smelting);
        rig.network().ensureTree();
        rig.network().root().remove(node.id());
        smelting.add(node);
        return smelting;
    }

    @GameTest(template = "empty")
    public static void parentSheetSwitchedOffStopsTheSheetsInIt(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        Sheet smelting = nestInSmelting(rig);
        rig.network().setSheetEnabled(smelting, false);
        check(helper, !rig.network().runs(rig.sheet()), "a sheet inside a switched-off sheet counts as running");
        helper.runAfterDelay(10, () -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 0, "a sheet inside a switched-off sheet ran");
            rig.network().setSheetEnabled(smelting, true);
            helper.runAfterDelay(10, () -> {
                check(helper, rig.count(1, Items.COBBLESTONE) == 8, "the sheet did not run once the sheet around it was back on");
                helper.succeed();
            });
        });
    }

    /** Items go from a barrel on the root, into a child sheet through its input pin, out through its output pin, on. */
    @GameTest(template = "empty")
    public static void flowCrossesAChildSheetThroughItsPins(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        rig.container(0).setItem(1, new ItemStack(Items.DIRT, 5));
        Sheet child = new Sheet(UUID.randomUUID(), "only ingots");
        Node in = child.add(Node.of(new NodeSettings.SheetPinSettings(true, "in")));
        Node filter = child.add(Node.of(new NodeSettings.FilterSettings("tag:c:ingots")));
        Node out = child.add(Node.of(new NodeSettings.SheetPinSettings(false, "out")));
        child.connect(Wire.of(in, filter));
        child.connect(Wire.of(filter, out));
        rig.network().sheets().add(child);
        rig.network().ensureTree();
        Node holder = rig.network().placement(child.id()).node();
        // The child sits in the root, beside the test sheet: wire the test sheet's nodes to it from the root.
        Sheet root = rig.network().root();
        Node source = root.add(Node.of(new NodeSettings.DeviceSettings(rig.device(0), List.of(),
                List.of(new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 64, 1, 0)))));
        Node sink = root.add(Node.of(new NodeSettings.DeviceSettings(rig.device(1),
                List.of(new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.empty(), List.of())), List.of())));
        root.setInterval(1);
        root.connect(Wire.of(source, holder));
        root.connect(Wire.of(holder, sink));
        helper.succeedWhen(() -> {
            check(helper, rig.compiled().errors().isEmpty(), "errors: " + rig.compiled().errors());
            check(helper, rig.count(1, Items.IRON_INGOT) == 5, "ingots did not pass through the child sheet");
            check(helper, rig.count(1, Items.DIRT) == 0 && rig.count(0, Items.DIRT) == 5, "the child sheet's filter was not applied");
        });
    }

    /** A signal into a sheet node's gate switches the whole sheet: an AND with nothing wired into it is off. */
    @GameTest(template = "empty")
    public static void gateOnASheetNodeStopsTheSheet(GameTestHelper helper) {
        Rig rig = rig(helper, Blocks.BARREL, Blocks.BARREL);
        rig.container(0).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        rig.wire(rig.source(0, ResourceKind.ITEM, List.of(), 64), rig.sink(1, ResourceKind.ITEM, List.of()));
        Node holder = rig.sheetNode();
        Sheet root = rig.network().root();
        Node off = root.add(Node.of(new NodeSettings.LogicSettings(NodeSettings.LogicOp.AND)));
        Wire gate = root.connect(new Wire(UUID.randomUUID(), off.id(), 0, holder.id(), Wire.GATE, 0));
        helper.runAfterDelay(10, () -> {
            check(helper, rig.count(1, Items.COBBLESTONE) == 0, "a sheet whose node's gate was off ran");
            check(helper, rig.runtime().sheetsOff().contains(rig.sheet().id()), "the sheet wasn't reported as switched off");
            root.disconnect(gate.id());
            helper.runAfterDelay(10, () -> {
                check(helper, rig.count(1, Items.COBBLESTONE) == 8, "the sheet did not run once its gate was gone");
                helper.succeed();
            });
        });
    }

    /** A lever wired to a lamp on the test sheet, the lamp's input set to {@code whileOff}; the lamp is lit once it runs. */
    private static Rig leverToLamp(GameTestHelper helper, NodeSettings.OffBehavior whileOff) {
        Rig rig = rig(helper, Blocks.LEVER, Blocks.BARREL, Blocks.REDSTONE_LAMP);
        helper.setBlock(rig.devices().get(0), lever(true));
        Node in = rig.redstoneFrom(rig.device(0));
        Node out = rig.node(new NodeSettings.DeviceSettings(rig.device(2),
                List.of(new NodeSettings.Input(ResourceKind.REDSTONE, List.of(), Optional.empty(), List.of(), whileOff)), List.of()));
        rig.signal(in, out, 0);
        return rig;
    }

    private static boolean lit(Rig rig) {
        return rig.helper().getBlockState(rig.devices().get(2)).getValue(RedstoneLampBlock.LIT);
    }

    /** Switched off, a sheet's redstone output holds: the lamp stays lit though the lever goes off, until the sheet is back on. */
    @GameTest(template = "empty")
    public static void switchedOffSheetHoldsItsRedstone(GameTestHelper helper) {
        Rig rig = leverToLamp(helper, NodeSettings.OffBehavior.HOLD);
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, lit(rig), "the lamp never lit"))
                .thenExecute(() -> {
                    rig.sheetNode();
                    rig.network().setSheetEnabled(rig.sheet(), false);
                })
                .thenExecuteAfter(5, () -> helper.setBlock(rig.devices().get(0), lever(false)))
                .thenExecuteAfter(10, () -> {
                    check(helper, lit(rig), "the lamp went out while its sheet was off (it should hold)");
                    rig.network().setSheetEnabled(rig.sheet(), true);
                })
                .thenWaitUntil(() -> check(helper, !lit(rig), "the lamp didn't follow the lever once the sheet was back on"))
                .thenSucceed();
    }

    /** Set to a fixed value while off, the output goes to it: 0 puts the lamp out as soon as the sheet is off. */
    @GameTest(template = "empty")
    public static void switchedOffSheetGivesItsFixedValue(GameTestHelper helper) {
        Rig rig = leverToLamp(helper, new NodeSettings.OffBehavior(NodeSettings.WhileOff.FIXED, 0));
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, lit(rig), "the lamp never lit"))
                .thenExecute(() -> {
                    rig.sheetNode();
                    rig.network().setSheetEnabled(rig.sheet(), false);
                })
                .thenWaitUntil(() -> check(helper, !lit(rig), "the lamp stayed lit, though its output goes to 0 while the sheet is off"))
                .thenSucceed();
    }

    /** Set to keep running, the logic feeding the output works on while its sheet is off: the lamp follows the lever. */
    @GameTest(template = "empty")
    public static void switchedOffSheetKeepsRunningWhatFeedsAnOutput(GameTestHelper helper) {
        Rig rig = leverToLamp(helper, new NodeSettings.OffBehavior(NodeSettings.WhileOff.RUN, 0));
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, lit(rig), "the lamp never lit"))
                .thenExecute(() -> {
                    rig.sheetNode();
                    rig.network().setSheetEnabled(rig.sheet(), false);
                })
                .thenExecuteAfter(5, () -> helper.setBlock(rig.devices().get(0), lever(false)))
                .thenWaitUntil(() -> check(helper, !lit(rig), "the lamp didn't follow the lever, though its output keeps running"))
                .thenSucceed();
    }

    /** The tree puts itself right: unplaced sheets go into the root, a second node for one sheet and loops come out. */
    @GameTest(template = "empty")
    public static void sheetTreeRepairsItself(GameTestHelper helper) {
        Network network = NetworkManager.create(helper.getLevel().getServer(), null);
        Sheet a = new Sheet(UUID.randomUUID(), "a");
        Sheet b = new Sheet(UUID.randomUUID(), "b");
        network.sheets().add(a);
        network.sheets().add(b);
        network.ensureTree();
        Sheet root = network.root();
        check(helper, network.placement(a.id()).parent() == root && network.placement(b.id()).parent() == root, "unplaced sheets weren't put in the root");
        // b inside a, and a second node for a inside b: a loop, and a sheet placed twice.
        Node bNode = network.placement(b.id()).node();
        root.remove(bNode.id());
        a.add(bNode);
        b.add(Node.of(new NodeSettings.SheetSettings(a.id())));
        network.ensureTree();
        check(helper, network.placement(b.id()).parent() == a, "b didn't stay inside a");
        check(helper, b.nodes().stream().noneMatch(n -> n.kind() == NodeSettings.Kind.SHEET), "a second node for a stayed in b");
        check(helper, network.descendants(root).containsAll(List.of(a, b)) && network.descendants(a).equals(List.of(b)), "the tree reads wrong");
        check(helper, network.path(b).equals("a \u203a b"), "path was " + network.path(b));
        NetworkManager.delete(helper.getLevel().getServer(), network);
        helper.succeed();
    }
}
