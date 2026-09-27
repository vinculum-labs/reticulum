package io.github.leadassimilator.reticulum.gametest;

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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Reticulum.MODID)
@PrefixGameTestTemplate(false)
public final class FilterTests {
    private FilterTests() {
    }

    private static boolean items(GameTestHelper helper, String filter, ItemStack stack, Map<String, String> templates) {
        try {
            return Filters.items(filter, templates).test(stack, Filters.Context.of(helper.getLevel()));
        } catch (FilterException e) {
            helper.fail("'" + filter + "' failed to compile: " + e.getMessage());
            return false;
        }
    }

    private static void expect(GameTestHelper helper, String filter, ItemStack stack, boolean expected) {
        boolean actual = items(helper, filter, stack, Map.of("ingots", "tag:c:ingots"));
        if (actual != expected) {
            helper.fail("'" + filter + "' on " + stack + " should be " + expected);
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

        for (String bad : List.of("(tag:c:ingots", "name:\"open", "@missing", "is:bogus", "dura:<abc")) {
            try {
                Filters.items(bad, Map.of());
                helper.fail("'" + bad + "' should not compile");
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
            helper.fail("fluid id filter");
        }
        if (!Filters.fluids("temp:>1000", Map.of()).test(lava, context) || Filters.fluids("temp:>1000", Map.of()).test(water, context)) {
            helper.fail("fluid temperature filter");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sheetsSurviveSaving(GameTestHelper helper) {
        Network network = new Network(UUID.randomUUID(), "test", null, "", AccessMode.PUBLIC);
        Sheet sheet = new Sheet(UUID.randomUUID(), "ore line");
        NodeSettings.Port port = new NodeSettings.Port(ResourceKind.ITEM, GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), List.of(), Optional.empty());
        Node source = sheet.add(Node.of(new NodeSettings.SourceSettings(port,
                List.of(new NodeSettings.Limit(NodeSettings.Scope.PER_TYPE, 16, "tag:c:ores")), 32, 0, 1)));
        Node kit = sheet.add(Node.of(new NodeSettings.KitSettings(List.of(new NodeSettings.KitLine("iron_ingot", 4)), 2)));
        sheet.connect(Wire.of(source, kit));
        network.sheets().add(sheet);
        network.filters().put("ores", "tag:c:ores");
        network.overflowItems().add(new ItemStack(Items.DIAMOND, 3));

        var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = Network.CODEC.encodeStart(ops, network).getOrThrow();
        Network decoded = Network.CODEC.parse(ops, encoded).getOrThrow();
        Sheet back = decoded.sheets().getFirst();
        if (back.nodes().size() != 2 || back.wires().size() != 1 || !back.name().equals("ore line")) {
            helper.fail("sheet did not round-trip");
        }
        if (!(back.node(source.id()).settings() instanceof NodeSettings.SourceSettings s) || s.perOperation() != 32 || s.keep().size() != 1) {
            helper.fail("source settings did not round-trip");
        }
        if (!"tag:c:ores".equals(decoded.filters().get("ores")) || decoded.overflowItems().getFirst().getCount() != 3) {
            helper.fail("filters or overflow did not round-trip");
        }
        helper.succeed();
    }
}
