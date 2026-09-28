package io.github.leadassimilator.reticulum.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.network.NetworkManager;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.topology.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
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
        // Interval 20 so exactly one operation happens in the first 20 ticks.
        rig.sheet().setInterval(20);
        rig.wire(rig.output(0, new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 0, 20, 0)),
                rig.sink(1, ResourceKind.ITEM, List.of()));
        helper.runAfterDelay(21, () -> {
            check(helper, rig.count(1, Items.ENDER_PEARL) == 16, "one stack of pearls is 16, moved " + rig.count(1, Items.ENDER_PEARL));
            helper.succeed();
        });
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
}
