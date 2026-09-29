package io.github.leadassimilator.reticulum.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.engine.NetworkRuntime;
import io.github.leadassimilator.reticulum.engine.NetworkTicker;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The spec's performance target: about 1,000 connectors and 2,000 flows in under 1 ms per tick on average. Runs in
 * its own batch, since it builds well outside its template, and clears up after itself.
 */
@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class BenchmarkTests {
    private static final int SIDE = 32;
    private static final int WARM_UP = 100;
    private static final int MEASURED = 200;
    private static final double TARGET_MS = 1.0;

    private BenchmarkTests() {
    }

    @GameTest(template = "empty", batch = "benchmark", timeoutTicks = 1200)
    public static void thousandConnectorsTwoThousandFlows(GameTestHelper helper) {
        // A 32 x 32 floor of connectors (joined to each other and to the controller), each with a barrel on top.
        List<BlockPos> barrels = new ArrayList<>();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                BlockPos barrel = new BlockPos(1 + x, 2, 1 + z);
                helper.setBlock(barrel, Blocks.BARREL);
                barrels.add(barrel);
            }
        }
        long buildStart = System.nanoTime();
        place(helper, new BlockPos(0, 1, 1), ModBlocks.CONTROLLER.get());
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                place(helper, new BlockPos(1 + x, 1, 1 + z), ModBlocks.CONNECTOR.get());
            }
        }
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
        Network network = NetworkManager.network(helper.getLevel().getServer(),
                Topology.networkOf(helper.getLevel(), Topology.blockEntity(helper.getLevel(), helper.absolutePos(new BlockPos(0, 1, 1)))));
        if (network == null || network.devices().size() != barrels.size()) {
            helper.fail("benchmark network did not build: " + (network == null ? "no network" : network.devices().size() + " devices"));
            return;
        }

        // One device node per barrel, each passing items on to two others: 2,048 routes.
        Sheet sheet = new Sheet(UUID.randomUUID(), "benchmark");
        sheet.setInterval(10);
        List<Node> nodes = new ArrayList<>();
        for (BlockPos barrel : barrels) {
            nodes.add(sheet.add(Node.of(new NodeSettings.DeviceSettings(Devices.global(helper.getLevel(), helper.absolutePos(barrel)),
                    List.of(new NodeSettings.Input(ResourceKind.ITEM, List.of(), Optional.empty(), List.of())),
                    List.of(new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(), List.of(), 1, 0, 0))))));
        }
        for (int i = 0; i < nodes.size(); i++) {
            sheet.connect(Wire.of(nodes.get(i), nodes.get((i + 1) % nodes.size())));
            sheet.connect(new Wire(UUID.randomUUID(), nodes.get(i).id(), 0, nodes.get((i + 7) % nodes.size()).id(), 0, 1));
        }
        network.sheets().add(sheet);
        // Something to move in a quarter of the barrels.
        for (int i = 0; i < barrels.size(); i += 4) {
            ((Container) helper.getBlockEntity(barrels.get(i))).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        }

        NetworkRuntime runtime = NetworkTicker.runtime(network.id());
        helper.runAfterDelay(WARM_UP + MEASURED, () -> {
            long[] stats = runtime.tickTimeStats();
            double averageMs = stats[0] / 1e6;
            double longestMs = stats[1] / 1e6;
            Reticulum.LOGGER.info("Benchmark: {} connectors, {} routes; built in {} ms; tick average {} ms, longest {} ms (last {} ticks)",
                    network.connectors().size(), sheet.wires().size(), buildMs, String.format("%.3f", averageMs), String.format("%.3f", longestMs),
                    MEASURED);
            // Clear the floor so later batches start clean.
            for (int x = -1; x <= SIDE + 1; x++) {
                for (int z = 0; z <= SIDE + 1; z++) {
                    for (int y = 1; y <= 2; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                    }
                }
            }
            NetworkManager.delete(helper.getLevel().getServer(), network);
            if (averageMs > TARGET_MS) {
                helper.fail(String.format("average tick %.3f ms is over the %.1f ms target (longest %.3f ms)", averageMs, TARGET_MS, longestMs));
            } else {
                helper.succeed();
            }
        });
    }

    private static void place(GameTestHelper helper, BlockPos rel, net.minecraft.world.level.block.Block block) {
        helper.setBlock(rel, block);
        Topology.onPlaced(helper.getLevel(), helper.absolutePos(rel), null, null);
    }
}
