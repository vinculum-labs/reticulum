package io.github.leadassimilator.reticulum.integration.mekanism;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.filter.FilterException;
import io.github.leadassimilator.reticulum.filter.FilterExpr;
import io.github.leadassimilator.reticulum.filter.Filters;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.datamaps.IMekanismDataMapTypes;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.capabilities.BlockCapability;

/**
 * Mekanism chemicals (gases, infusion types, pigments and slurries, one type since Mekanism 10.7). Loaded only when
 * Mekanism is present.
 */
public final class MekanismChemicals {
    /** The same capability Mekanism registers; creating it again by name returns Mekanism's instance. */
    public static final BlockCapability<IChemicalHandler, Direction> CAPABILITY =
            BlockCapability.createSided(ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    private MekanismChemicals() {
    }

    public static void init() {
        Filters.setChemicalCompiler(MekanismChemicals::compile);
    }

    public static ResourceLocation id(ChemicalStack stack) {
        return MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical());
    }

    /** A stack of the chemical with this id, or null when no such chemical exists (any more). */
    @Nullable
    public static ChemicalStack stack(ResourceLocation id, long amount) {
        return MekanismAPI.CHEMICAL_REGISTRY.getHolder(id)
                .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
                .map(holder -> new ChemicalStack(holder, amount))
                .orElse(null);
    }

    /** What chemicals a block holds, by id, for the editor's filter picker (the largest amount any one side shows). */
    public static void collect(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, java.util.Map<ResourceLocation, Long> totals) {
        java.util.Set<IChemicalHandler> handlers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        IChemicalHandler unsided = level.getCapability(CAPABILITY, pos, null);
        if (unsided != null) {
            handlers.add(unsided);
        }
        for (Direction side : Direction.values()) {
            IChemicalHandler handler = level.getCapability(CAPABILITY, pos, side);
            if (handler != null) {
                handlers.add(handler);
            }
        }
        for (IChemicalHandler handler : handlers) {
            java.util.Map<ResourceLocation, Long> one = new java.util.LinkedHashMap<>();
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack stack = handler.getChemicalInTank(tank);
                if (!stack.isEmpty()) {
                    one.merge(id(stack), stack.getAmount(), Long::sum);
                }
            }
            one.forEach((id, amount) -> totals.merge(id, amount, Math::max));
        }
    }

    // ---------------------------------------------------------------- filters

    private static Filters.ChemicalFilter compile(FilterExpr expr) throws FilterException {
        return switch (expr) {
            case FilterExpr.Any any -> (s, c) -> true;
            case FilterExpr.Not not -> {
                Filters.ChemicalFilter inner = compile(not.term());
                yield (s, c) -> !inner.test(s, c);
            }
            case FilterExpr.And and -> {
                List<Filters.ChemicalFilter> terms = new ArrayList<>();
                for (FilterExpr term : and.terms()) {
                    terms.add(compile(term));
                }
                yield (s, c) -> terms.stream().allMatch(f -> f.test(s, c));
            }
            case FilterExpr.Or or -> {
                List<Filters.ChemicalFilter> terms = new ArrayList<>();
                for (FilterExpr term : or.terms()) {
                    terms.add(compile(term));
                }
                yield (s, c) -> terms.stream().anyMatch(f -> f.test(s, c));
            }
            case FilterExpr.Atom atom -> atom(atom);
            case FilterExpr.Ref ref -> throw new FilterException("Unresolved template @" + ref.name(), -1);
        };
    }

    private static Filters.ChemicalFilter atom(FilterExpr.Atom atom) throws FilterException {
        String value = atom.value();
        return switch (atom.key()) {
            case "id" -> {
                Predicate<String> id = Filters.idMatcher(value);
                yield (s, c) -> id.test(id((ChemicalStack) s).toString());
            }
            case "tag" -> {
                TagKey<Chemical> tag = TagKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME, Filters.location(value));
                yield (s, c) -> ((ChemicalStack) s).is(tag);
            }
            case "mod" -> (s, c) -> id((ChemicalStack) s).getNamespace().equals(value);
            case "name" -> {
                Predicate<String> name = Filters.textMatcher(atom);
                yield (s, c) -> name.test(((ChemicalStack) s).getTextComponent().getString());
            }
            case "chem" -> switch (value) {
                case "radioactive" -> (s, c) -> ((ChemicalStack) s).isRadioactive();
                case "coolant" -> (s, c) -> ((ChemicalStack) s).getData(IMekanismDataMapTypes.INSTANCE.cooledChemicalCoolant()) != null
                        || ((ChemicalStack) s).getData(IMekanismDataMapTypes.INSTANCE.heatedChemicalCoolant()) != null;
                case "fuel" -> (s, c) -> ((ChemicalStack) s).getData(IMekanismDataMapTypes.INSTANCE.chemicalFuel()) != null;
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
