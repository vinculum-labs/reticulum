package io.github.leadassimilator.reticulum.integration.mekanism;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.FilterExpr;
import io.github.leadassimilator.reticulum.filter.Filters;
import io.github.leadassimilator.reticulum.protocol.web.DeviceContents;
import io.github.leadassimilator.reticulum.registry.ModBlockEntities;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.datamaps.IMekanismDataMapTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Mekanism chemicals (gases, infusion types, pigments and slurries, one type since Mekanism 10.7). Loaded only when
 * Mekanism is present.
 */
public final class MekanismChemicals {
    /** The same capability Mekanism registers; creating it again by name returns Mekanism's instance. */
    public static final BlockCapability<IChemicalHandler, Direction> CAPABILITY =
            BlockCapability.createSided(Identifier.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    private MekanismChemicals() {
    }

    /** No tanks: what a connector offers on its device sides (see ConnectorBlockEntity.registerCapabilities). */
    private static final IChemicalHandler NONE = new IChemicalHandler() {
        @Override
        public int getChemicalTanks() {
            return 0;
        }

        @Override
        public ChemicalStack getChemicalInTank(int tank) {
            return ChemicalStack.EMPTY;
        }

        @Override
        public void setChemicalInTank(int tank, ChemicalStack stack) {
        }

        @Override
        public long getChemicalTankCapacity(int tank) {
            return 0;
        }

        @Override
        public boolean isValid(int tank, ChemicalStack stack) {
            return false;
        }

        @Override
        public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
            return stack;
        }

        @Override
        public ChemicalStack extractChemical(int tank, long amount, Action action) {
            return ChemicalStack.EMPTY;
        }
    };

    public static void init() {
        Filters.setChemicalCompiler(MekanismChemicals::compile);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(CAPABILITY, ModBlockEntities.CONNECTOR.get(), (connector, side) -> connector.facesDevice(side) ? NONE : null);
    }

    public static Identifier id(ChemicalStack stack) {
        return MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical());
    }

    /** A stack of the chemical with this id, or null when no such chemical exists (any more). */
    @Nullable
    public static ChemicalStack stack(Identifier id, long amount) {
        return MekanismAPI.CHEMICAL_REGISTRY.getHolder(id)
                .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
                .map(holder -> new ChemicalStack(holder, amount))
                .orElse(null);
    }

    /** What chemicals a block holds, by id, for the editor's filter picker (the largest amount any one side shows). */
    public static void collect(ServerLevel level, BlockPos pos, Map<Identifier, Long> totals) {
        for (IChemicalHandler handler : DeviceContents.handlers(level, pos, CAPABILITY)) {
            Map<Identifier, Long> one = new LinkedHashMap<>();
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack stack = handler.getChemicalInTank(tank);
                if (!stack.isEmpty()) {
                    one.merge(id(stack), stack.getAmount(), Long::sum);
                }
            }
            DeviceContents.keepLargest(totals, one);
        }
    }

    // ---------------------------------------------------------------- filters

    private static Filters.ChemicalFilter compile(FilterExpr expr) throws FilterException {
        Filters.Test<ChemicalStack> test = Filters.combine(expr, MekanismChemicals::atom);
        return (s, c) -> s instanceof ChemicalStack stack && test.test(stack, c);
    }

    private static Filters.Test<ChemicalStack> atom(FilterExpr.Atom atom) throws FilterException {
        Filters.Test<ChemicalStack> common = Filters.commonAtom(atom, MekanismChemicals::id, location -> {
            TagKey<Chemical> tag = TagKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME, location);
            return s -> s.is(tag);
        }, s -> s.getTextComponent().getString());
        if (common != null) {
            return common;
        }
        String value = atom.value();
        return switch (atom.key()) {
            case "chem" -> switch (value) {
                case "radioactive" -> (s, c) -> s.isRadioactive();
                case "coolant" -> (s, c) -> s.getData(IMekanismDataMapTypes.INSTANCE.cooledChemicalCoolant()) != null
                        || s.getData(IMekanismDataMapTypes.INSTANCE.heatedChemicalCoolant()) != null;
                case "fuel" -> (s, c) -> s.getData(IMekanismDataMapTypes.INSTANCE.chemicalFuel()) != null;
                default -> throw new FilterException("Unknown chemical property chem:" + value + " (radioactive, coolant, fuel)", -1);
            };
            case "in" -> {
                Filters.requireSink(value);
                yield (s, c) -> c.chemicalsInSink() != null && c.chemicalsInSink().test(s);
            }
            default -> throw new FilterException("'" + atom.key() + ":' doesn't apply to chemicals", -1);
        };
    }
}
