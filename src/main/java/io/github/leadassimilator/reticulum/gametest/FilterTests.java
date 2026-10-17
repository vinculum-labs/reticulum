package io.github.leadassimilator.reticulum.gametest;

import static io.github.leadassimilator.reticulum.gametest.TestSupport.fail;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.graph.Node;
import io.github.leadassimilator.reticulum.graph.NodeSettings;
import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.graph.Wire;
import io.github.leadassimilator.reticulum.network.Network;
import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

public final class FilterTests {
    private FilterTests() {
    }

    private static boolean items(GameTestHelper helper, String filter, ItemStack stack, Map<String, String> templates) {
        try {
            return Filters.items(filter, templates).test(stack, Filters.Context.of(helper.getLevel()));
        } catch (FilterException e) {
            fail(helper, "'" + filter + "' failed to compile: " + e.getMessage());
            return false;
        }
    }

    private static void expect(GameTestHelper helper, String filter, ItemStack stack, boolean expected) {
        boolean actual = items(helper, filter, stack, Map.of("ingots", "tag:c:ingots"));
        if (actual != expected) {
            fail(helper, "'" + filter + "' on " + stack + " should be " + expected);
        }
    }

    @GameTest(template = "empty")
    public static void itemFilterLanguage(GameTestHelper helper) {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack ore = new ItemStack(Items.IRON_ORE);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(sword.getMaxDamage() - 10);

        expect(helper, "", iron, true);
        expect(helper, "*", iron, true);
        expect(helper, "minecraft:iron_ingot", iron, true);
        expect(helper, "iron_ingot", iron, true);
        expect(helper, "minecraft:*_ore", ore, true);
        expect(helper, "minecraft:*_ore", iron, false);
        expect(helper, "#c:ingots", iron, true);
        expect(helper, "tag:c:ingots | tag:c:ores", ore, true);
        expect(helper, "(tag:c:ingots | tag:c:ores) & !id:iron_ore", ore, false);
        expect(helper, "tag:c:ingots !mod:create", iron, true);
        expect(helper, "mod:minecraft", iron, true);
        expect(helper, "name:\"iron ingot\"", iron, true);
        expect(helper, "name:/^Iron/", iron, true);
        expect(helper, "@ingots", iron, true);
        expect(helper, "@ingots", ore, false);
        expect(helper, "dura:<5%", sword, true);
        expect(helper, "dura:>=100", sword, false);
        expect(helper, "is:damaged", sword, true);
        expect(helper, "is:stackable", sword, false);
        expect(helper, "is:fuel", new ItemStack(Items.COAL), true);
        expect(helper, "is:smeltable", ore, true);
        expect(helper, "*_ore", ore, true);
        expect(helper, "*_ore", iron, false);
        expect(helper, "*ingot*", iron, true);
        expect(helper, "* !*_ore", iron, true);
        expect(helper, "id:/_ore$/", ore, true);
        expect(helper, "id:/_ore$/", iron, false);
        expect(helper, "mod:/^mine/", iron, true);
        // Terms for fluids only are fine as a filter (they match no items), but not a typo.
        try {
            Filters.checkAny("temp:>1000", Map.of());
        } catch (FilterException e) {
            fail(helper, "temp:>1000 should be a valid filter: " + e.getMessage());
        }
        try {
            Filters.checkAny("tmep:>1000 | (", Map.of());
            fail(helper, "a broken filter passed checkAny");
        } catch (FilterException expected) {
            // expected
        }

        for (String bad : List.of("(tag:c:ingots", "name:\"open", "@missing", "is:bogus", "dura:<abc", "tag:/ores/")) {
            try {
                Filters.items(bad, Map.of());
                fail(helper, "'" + bad + "' should not compile");
            } catch (FilterException expected) {
                // expected
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fluidFilterLanguage(GameTestHelper helper) throws FilterException {
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        FluidStack lava = new FluidStack(Fluids.LAVA, 1000);
        Filters.Context context = Filters.Context.of(helper.getLevel());
        if (!Filters.fluids("minecraft:water", Map.of()).test(water, context) || Filters.fluids("minecraft:water", Map.of()).test(lava, context)) {
            fail(helper, "fluid id filter");
        }
        if (!Filters.fluids("temp:>1000", Map.of()).test(lava, context) || Filters.fluids("temp:>1000", Map.of()).test(water, context)) {
            fail(helper, "fluid temperature filter");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sheetsSurviveSaving(GameTestHelper helper) {
        Network network = new Network(UUID.randomUUID(), "test", null, "", AccessMode.PUBLIC);
        Sheet sheet = new Sheet(UUID.randomUUID(), "ore line");
        Node source = sheet.add(Node.of(new NodeSettings.DeviceSettings(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), List.of(),
                List.of(new NodeSettings.Output(ResourceKind.ITEM, List.of(), Optional.empty(),
                        List.of(new NodeSettings.Limit(NodeSettings.Scope.PER_TYPE, 16, "tag:c:ores")), 32, 0, 1)))));
        Node kit = sheet.add(Node.of(new NodeSettings.KitSettings(List.of(new NodeSettings.KitLine("iron_ingot", 4)), 2)));
        sheet.connect(Wire.of(source, kit));
        network.sheets().add(sheet);
        network.filters().put("ores", "tag:c:ores");
        network.overflowItems().add(new ItemStack(Items.DIAMOND, 3));

        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = Network.CODEC.encodeStart(ops, network).getOrThrow();
        Network decoded = Network.CODEC.parse(ops, encoded).getOrThrow();
        // Loading gives the network its root sheet, and places the sheet in it.
        Sheet back = decoded.sheet(sheet.id());
        if (back == null || decoded.placement(back.id()) == null || decoded.placement(back.id()).parent() != decoded.root()) {
            fail(helper, "the sheet wasn't placed in the root when loaded");
        }
        if (back.nodes().size() != 2 || back.wires().size() != 1 || !back.name().equals("ore line")) {
            fail(helper, "sheet did not round-trip");
        }
        if (!(back.node(source.id()).settings() instanceof NodeSettings.DeviceSettings s) || s.outputs().getFirst().perOperation() != 32
                || s.outputs().getFirst().keep().size() != 1) {
            fail(helper, "source settings did not round-trip");
        }
        if (!"tag:c:ores".equals(decoded.filters().get("ores")) || decoded.overflowItems().getFirst().getCount() != 3) {
            fail(helper, "filters or overflow did not round-trip");
        }
        helper.succeed();
    }
}
