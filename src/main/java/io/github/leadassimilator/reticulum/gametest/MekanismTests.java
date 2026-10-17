package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.check;
import static io.github.leadassimilator.reticulum.gametest.TestSupport.place;
import static io.github.leadassimilator.reticulum.gametest.EngineTests.SLOT_FACES;
import static io.github.leadassimilator.reticulum.gametest.EngineTests.rig;
import static io.github.leadassimilator.reticulum.gametest.EngineTests.energyStored;
import static io.github.leadassimilator.reticulum.gametest.EngineTests.fillEnergy;
import static io.github.leadassimilator.reticulum.gametest.EngineTests.fluidStored;

import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.network.Devices;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import io.github.leadassimilator.reticulum.topology.Topology;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import io.github.leadassimilator.reticulum.gametest.EngineTests.Rig;
import io.github.leadassimilator.reticulum.Reticulum;

/** Game tests with Mekanism's chemicals, tanks and machines. Left out of builds without Mekanism support. */
public final class MekanismTests {
    private MekanismTests() {
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

    /**
     * A Mekanism cable beside a connector draws its arm to it (the connector offers empty handlers on the sides facing a
     * device), and lets go once that side is detached.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mekanismCableConnectsToAConnectorsDeviceSideOnly(GameTestHelper helper) {
        if (!io.github.leadassimilator.reticulum.integration.Mods.MEKANISM) {
            helper.succeed();
            return;
        }
        place(helper, new BlockPos(1, 1, 1), ModBlocks.CONTROLLER.get(), null);
        place(helper, new BlockPos(2, 1, 1), ModBlocks.CONNECTOR.get(), Direction.WEST);
        helper.setBlock(new BlockPos(3, 1, 1), MekanismTestSupport.block("basic_universal_cable"));
        BlockPos connector = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos cable = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, MekanismTestSupport.transmitterConnects(helper.getLevel(), cable, Direction.WEST),
                        "the cable doesn't connect to the connector's device side"))
                .thenExecute(() -> {
                    check(helper, helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, connector, Direction.WEST) == null,
                            "the connector offers a handler toward its controller");
                    Topology.toggle(helper.getLevel(), connector, Direction.EAST, null);
                })
                .thenWaitUntil(() -> check(helper, !MekanismTestSupport.transmitterConnects(helper.getLevel(), cable, Direction.WEST),
                        "the cable stays connected to a detached side"))
                .thenSucceed();
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
}
