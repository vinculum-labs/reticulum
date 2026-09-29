package io.github.leadassimilator.reticulum.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Compiles parsed filters into predicates for items, fluids and (with Mekanism) chemicals. Energy has no types, so
 * energy flows ignore filters.
 */
public final class Filters {
    private static final int MAX_TEMPLATE_DEPTH = 16;
    private static final int CACHE_SIZE = 256;

    private Filters() {
    }

    /** What a predicate may look at besides the resource itself. {@code chemicalsInSink} is given Mekanism chemical stacks. */
    public record Context(Level level, @Nullable Predicate<ItemStack> itemsInSink, @Nullable Predicate<FluidStack> fluidsInSink,
            @Nullable Predicate<Object> chemicalsInSink) {
        public Context(Level level, @Nullable Predicate<ItemStack> itemsInSink, @Nullable Predicate<FluidStack> fluidsInSink) {
            this(level, itemsInSink, fluidsInSink, null);
        }

        public static Context of(Level level) {
            return new Context(level, null, null, null);
        }
    }

    public interface ItemFilter {
        ItemFilter ANY = (stack, context) -> true;

        boolean test(ItemStack stack, Context context);
    }

    public interface FluidFilter {
        FluidFilter ANY = (stack, context) -> true;

        boolean test(FluidStack stack, Context context);
    }

    /** A filter over Mekanism chemical stacks, typed loosely so this class never loads Mekanism's. */
    public interface ChemicalFilter {
        ChemicalFilter NONE = (stack, context) -> false;

        boolean test(Object stack, Context context);
    }

    /** Compiles a resolved filter for chemicals; installed by the Mekanism integration. */
    public interface ChemicalCompiler {
        ChemicalFilter compile(FilterExpr expr) throws FilterException;
    }

    @Nullable
    private static ChemicalCompiler chemicalCompiler;

    public static void setChemicalCompiler(ChemicalCompiler compiler) {
        chemicalCompiler = compiler;
    }

    // ---------------------------------------------------------------- entry points

    public static ItemFilter items(String expression, Map<String, String> templates) throws FilterException {
        FilterExpr expr = resolve(FilterParser.parse(expression), templates, 0);
        ItemFilter compiled = compileItems(expr);
        return usesContext(expr) ? compiled : cached(compiled);
    }

    public static FluidFilter fluids(String expression, Map<String, String> templates) throws FilterException {
        return compileFluids(resolve(FilterParser.parse(expression), templates, 0));
    }

    public static ChemicalFilter chemicals(String expression, Map<String, String> templates) throws FilterException {
        if (chemicalCompiler == null) {
            throw new FilterException("Chemicals need Mekanism", -1);
        }
        return chemicalCompiler.compile(resolve(FilterParser.parse(expression), templates, 0));
    }

    /** Replaces template references with their parsed expressions, refusing cycles. */
    private static FilterExpr resolve(FilterExpr expr, Map<String, String> templates, int depth) throws FilterException {
        if (depth > MAX_TEMPLATE_DEPTH) {
            throw new FilterException("Filter templates refer to each other in a loop", -1);
        }
        return switch (expr) {
            case FilterExpr.Ref ref -> {
                String body = templates.get(ref.name());
                if (body == null) {
                    throw new FilterException("Unknown filter template @" + ref.name(), -1);
                }
                yield resolve(FilterParser.parse(body), templates, depth + 1);
            }
            case FilterExpr.And and -> new FilterExpr.And(resolveAll(and.terms(), templates, depth));
            case FilterExpr.Or or -> new FilterExpr.Or(resolveAll(or.terms(), templates, depth));
            case FilterExpr.Not not -> new FilterExpr.Not(resolve(not.term(), templates, depth));
            default -> expr;
        };
    }

    private static List<FilterExpr> resolveAll(List<FilterExpr> terms, Map<String, String> templates, int depth) throws FilterException {
        List<FilterExpr> out = new ArrayList<>();
        for (FilterExpr term : terms) {
            out.add(resolve(term, templates, depth));
        }
        return out;
    }

    private static boolean usesContext(FilterExpr expr) {
        return switch (expr) {
            case FilterExpr.And and -> and.terms().stream().anyMatch(Filters::usesContext);
            case FilterExpr.Or or -> or.terms().stream().anyMatch(Filters::usesContext);
            case FilterExpr.Not not -> usesContext(not.term());
            case FilterExpr.Atom atom -> atom.key().equals("in");
            default -> false;
        };
    }

    /** Context-free filters remember their answer per item and components, so each distinct stack is checked once. */
    private static ItemFilter cached(ItemFilter filter) {
        record Key(Item item, DataComponentPatch components) {
        }
        Map<Key, Boolean> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Boolean> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        return (stack, context) -> cache.computeIfAbsent(new Key(stack.getItem(), stack.getComponentsPatch()), k -> filter.test(stack, context));
    }

    // ---------------------------------------------------------------- items

    private static ItemFilter compileItems(FilterExpr expr) throws FilterException {
        return switch (expr) {
            case FilterExpr.Any any -> ItemFilter.ANY;
            case FilterExpr.Not not -> {
                ItemFilter inner = compileItems(not.term());
                yield (s, c) -> !inner.test(s, c);
            }
            case FilterExpr.And and -> {
                List<ItemFilter> terms = new ArrayList<>();
                for (FilterExpr term : and.terms()) {
                    terms.add(compileItems(term));
                }
                yield (s, c) -> {
                    for (ItemFilter f : terms) {
                        if (!f.test(s, c)) {
                            return false;
                        }
                    }
                    return true;
                };
            }
            case FilterExpr.Or or -> {
                List<ItemFilter> terms = new ArrayList<>();
                for (FilterExpr term : or.terms()) {
                    terms.add(compileItems(term));
                }
                yield (s, c) -> {
                    for (ItemFilter f : terms) {
                        if (f.test(s, c)) {
                            return true;
                        }
                    }
                    return false;
                };
            }
            case FilterExpr.Atom atom -> itemAtom(atom);
            case FilterExpr.Ref ref -> throw new FilterException("Unresolved template @" + ref.name(), -1);
        };
    }

    private static ItemFilter itemAtom(FilterExpr.Atom atom) throws FilterException {
        String value = atom.value();
        return switch (atom.key()) {
            case "id" -> {
                Predicate<String> id = idMatcher(value);
                yield (s, c) -> id.test(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            }
            case "tag" -> {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, location(value));
                yield (s, c) -> s.is(tag);
            }
            case "mod" -> (s, c) -> BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals(value);
            case "name" -> {
                Predicate<String> name = textMatcher(atom);
                yield (s, c) -> name.test(s.getHoverName().getString());
            }
            case "dura" -> {
                Comparison cmp = Comparison.parse(value);
                yield (s, c) -> {
                    if (!s.isDamageableItem()) {
                        return false;
                    }
                    int remaining = s.getMaxDamage() - s.getDamageValue();
                    return cmp.percent ? cmp.test(remaining * 100.0 / s.getMaxDamage()) : cmp.test(remaining);
                };
            }
            case "ench" -> enchantment(value);
            case "has" -> {
                DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(location(value));
                if (type == null) {
                    throw new FilterException("Unknown data component " + value, -1);
                }
                yield (s, c) -> s.has(type);
            }
            case "is" -> itemProperty(value);
            case "in" -> {
                requireSink(value);
                yield (s, c) -> c.itemsInSink() != null && c.itemsInSink().test(s);
            }
            default -> throw new FilterException("'" + atom.key() + ":' doesn't apply to items", -1);
        };
    }

    private static ItemFilter enchantment(String value) throws FilterException {
        if (value.equals("any")) {
            return (s, c) -> s.isEnchanted() || !s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
        }
        int op = indexOfOperator(value);
        String id = op < 0 ? value : value.substring(0, op);
        Comparison level = op < 0 ? Comparison.parse(">=1") : Comparison.parse(value.substring(op));
        Predicate<String> idMatch = idMatcher(id);
        return (s, c) -> matchesEnchantment(s.getEnchantments(), idMatch, level)
                || matchesEnchantment(s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY), idMatch, level);
    }

    private static boolean matchesEnchantment(ItemEnchantments enchantments, Predicate<String> id, Comparison level) {
        for (var entry : enchantments.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            String key = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
            if (id.test(key) && level.test(entry.getIntValue())) {
                return true;
            }
        }
        return false;
    }

    private static ItemFilter itemProperty(String value) throws FilterException {
        return switch (value) {
            case "fuel" -> (s, c) -> s.getBurnTime(RecipeType.SMELTING) > 0;
            case "food" -> (s, c) -> s.has(DataComponents.FOOD);
            case "stackable" -> (s, c) -> s.isStackable();
            case "damaged" -> (s, c) -> s.isDamaged();
            case "enchanted" -> (s, c) -> s.isEnchanted();
            case "smeltable" -> (s, c) -> c.level().getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(s), c.level()).isPresent();
            default -> throw new FilterException("Unknown property is:" + value
                    + " (fuel, food, stackable, damaged, enchanted, smeltable)", -1);
        };
    }

    // ---------------------------------------------------------------- fluids

    private static FluidFilter compileFluids(FilterExpr expr) throws FilterException {
        return switch (expr) {
            case FilterExpr.Any any -> FluidFilter.ANY;
            case FilterExpr.Not not -> {
                FluidFilter inner = compileFluids(not.term());
                yield (s, c) -> !inner.test(s, c);
            }
            case FilterExpr.And and -> {
                List<FluidFilter> terms = new ArrayList<>();
                for (FilterExpr term : and.terms()) {
                    terms.add(compileFluids(term));
                }
                yield (s, c) -> terms.stream().allMatch(f -> f.test(s, c));
            }
            case FilterExpr.Or or -> {
                List<FluidFilter> terms = new ArrayList<>();
                for (FilterExpr term : or.terms()) {
                    terms.add(compileFluids(term));
                }
                yield (s, c) -> terms.stream().anyMatch(f -> f.test(s, c));
            }
            case FilterExpr.Atom atom -> fluidAtom(atom);
            case FilterExpr.Ref ref -> throw new FilterException("Unresolved template @" + ref.name(), -1);
        };
    }

    private static FluidFilter fluidAtom(FilterExpr.Atom atom) throws FilterException {
        String value = atom.value();
        return switch (atom.key()) {
            case "id" -> {
                Predicate<String> id = idMatcher(value);
                yield (s, c) -> id.test(BuiltInRegistries.FLUID.getKey(s.getFluid()).toString());
            }
            case "tag" -> {
                TagKey<Fluid> tag = TagKey.create(Registries.FLUID, location(value));
                yield (s, c) -> s.is(tag);
            }
            case "mod" -> (s, c) -> BuiltInRegistries.FLUID.getKey(s.getFluid()).getNamespace().equals(value);
            case "name" -> {
                Predicate<String> name = textMatcher(atom);
                yield (s, c) -> name.test(s.getHoverName().getString());
            }
            case "temp" -> {
                Comparison cmp = Comparison.parse(value);
                yield (s, c) -> cmp.test(s.getFluidType().getTemperature(s));
            }
            case "is" -> switch (value) {
                case "gaseous" -> (s, c) -> s.getFluidType().isLighterThanAir();
                default -> throw new FilterException("Unknown fluid property is:" + value + " (gaseous)", -1);
            };
            case "in" -> {
                requireSink(value);
                yield (s, c) -> c.fluidsInSink() != null && c.fluidsInSink().test(s);
            }
            default -> throw new FilterException("'" + atom.key() + ":' doesn't apply to fluids", -1);
        };
    }

    // ---------------------------------------------------------------- shared helpers

    public static void requireSink(String value) throws FilterException {
        if (!value.equals("sink")) {
            throw new FilterException("Only in:sink is supported so far", -1);
        }
    }

    public static ResourceLocation location(String value) throws FilterException {
        ResourceLocation rl = ResourceLocation.tryParse(value);
        if (rl == null) {
            throw new FilterException("Not a valid id: " + value, -1);
        }
        return rl;
    }

    /** Glob over the full id; a pattern without a namespace matches any namespace. */
    public static Predicate<String> idMatcher(String pattern) {
        String full = pattern.contains(":") ? pattern : "*:" + pattern;
        StringBuilder regex = new StringBuilder();
        for (char ch : full.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        Pattern compiled = Pattern.compile(regex.toString());
        return id -> compiled.matcher(id).matches();
    }

    /** {@code /regex/} searches; any other value is a case-insensitive "contains". */
    public static Predicate<String> textMatcher(FilterExpr.Atom atom) throws FilterException {
        if (atom.regex()) {
            try {
                Pattern pattern = Pattern.compile(atom.value());
                return text -> pattern.matcher(text).find();
            } catch (PatternSyntaxException e) {
                throw new FilterException("Bad regex: " + e.getDescription(), -1);
            }
        }
        String needle = atom.value().toLowerCase(Locale.ROOT);
        return text -> text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static int indexOfOperator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<' || c == '>' || c == '=') {
                return i;
            }
        }
        return -1;
    }

    /** {@code <n}, {@code <=n}, {@code >n}, {@code >=n}, {@code =n}, optionally with a trailing {@code %}. */
    private record Comparison(String op, double number, boolean percent) {
        static final Set<String> OPS = new HashSet<>(List.of("<", "<=", ">", ">=", "="));

        static Comparison parse(String text) throws FilterException {
            int digits = 0;
            while (digits < text.length() && "<>=".indexOf(text.charAt(digits)) >= 0) {
                digits++;
            }
            String op = digits == 0 ? "=" : text.substring(0, digits);
            if (!OPS.contains(op)) {
                throw new FilterException("Unknown comparison " + op, -1);
            }
            String rest = text.substring(digits);
            boolean percent = rest.endsWith("%");
            if (percent) {
                rest = rest.substring(0, rest.length() - 1);
            }
            try {
                return new Comparison(op, Double.parseDouble(rest), percent);
            } catch (NumberFormatException e) {
                throw new FilterException("Not a number: " + rest, -1);
            }
        }

        boolean test(double value) {
            return switch (op) {
                case "<" -> value < number;
                case "<=" -> value <= number;
                case ">" -> value > number;
                case ">=" -> value >= number;
                default -> value == number;
            };
        }
    }
}
