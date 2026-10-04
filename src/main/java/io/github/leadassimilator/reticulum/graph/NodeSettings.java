package io.github.leadassimilator.reticulum.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Ids;
import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/** The settings of one node, one record per node kind. */
public sealed interface NodeSettings {
    Kind kind();

    enum Kind implements LowercaseName {
        /** Engine-internal: one output of a device node. Never saved or sent. */
        SOURCE(null, false),
        /** Engine-internal: one input of a device node. Never saved or sent. */
        SINK(null, false),
        FILTER(FilterSettings.CODEC, false),
        RATE_LIMIT(RateLimitSettings.CODEC, false),
        SPLITTER(SplitterSettings.CODEC, false),
        KIT(KitSettings.CODEC, false),
        DEVICE(DeviceSettings.CODEC, false),
        /** A child sheet, placed on its parent: its pins are its Sheet input and Sheet output nodes. */
        SHEET(SheetSettings.CODEC, false),
        INSTANCE(InstanceSettings.CODEC, false),
        SHEET_INPUT(SheetPinSettings.INPUT_CODEC, false),
        SHEET_OUTPUT(SheetPinSettings.OUTPUT_CODEC, false),
        /** Engine-internal: a device's redstone output pin. Never saved or sent. */
        REDSTONE_IN(null, true),
        /** Engine-internal: a device's redstone input pin. Never saved or sent. */
        REDSTONE_OUT(null, true),
        LEVEL(LevelSettings.CODEC, true),
        COMPARE(CompareSettings.CODEC, true),
        LOGIC(LogicSettings.CODEC, true),
        CONSTANT(ConstantSettings.CODEC, true),
        TIMER(TimerSettings.CODEC, true),
        PULSE(PulseSettings.CODEC, true),
        LATCH(LatchSettings.CODEC, true),
        COUNTER(CounterSettings.CODEC, true),
        DELAY(DelaySettings.CODEC, true),
        ALARM(AlarmSettings.CODEC, true),
        SEQUENCER(SequencerSettings.CODEC, true),
        SWITCH(SwitchSettings.CODEC, true),
        DISPLAY(DisplaySettings.CODEC, true),
        /** Engine-internal: a device's ME pin, resolved to the connector side it touches. Never saved or sent. */
        ME_PORT(null, false);

        /** Only the kinds a sheet can hold; the engine-internal ones can't be read or written. */
        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(() -> java.util.Arrays.stream(values())
                .filter(kind -> kind.codec != null).toArray(Kind[]::new));

        private final MapCodec<? extends NodeSettings> codec;
        private final boolean signal;

        Kind(MapCodec<? extends NodeSettings> codec, boolean signal) {
            this.codec = codec;
            this.signal = signal;
        }

        public MapCodec<? extends NodeSettings> codec() {
            return codec;
        }

        /** Signal nodes take and give integers on signal wires; they never carry resources. */
        public boolean signal() {
            return signal;
        }
    }

    Codec<NodeSettings> CODEC = Kind.CODEC.dispatch("kind", NodeSettings::kind, Kind::codec);

    /** Which device faces a source or sink uses. An empty face list means every face that answers. */
    record Port(ResourceKind resource, GlobalPos device, List<FaceKey> faces, Optional<SlotRange> slots) {
    }

    /** Inclusive slot range; only meaningful for items. */
    record SlotRange(int first, int last) {
        public static final Codec<SlotRange> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("first").forGetter(SlotRange::first),
                Codec.INT.fieldOf("last").forGetter(SlotRange::last)
        ).apply(i, SlotRange::new));

        public boolean contains(int slot) {
            return slot >= first && slot <= last;
        }
    }

    /** How a keep rule or insert cap counts. */
    enum Scope implements LowercaseName {
        /** Everything in the port together. */
        TOTAL,
        /** Each item or fluid type on its own. */
        PER_TYPE,
        /** Everything matching the rule's filter together. */
        FILTER;

        public static final Codec<Scope> CODEC = StringRepresentable.fromEnum(Scope::values);
    }

    /**
     * On a source: leave at least {@code amount} behind. On a sink: stop inserting once {@code amount} is present.
     * {@code filter} limits which resources the rule applies to (all of them when empty).
     */
    record Limit(Scope scope, long amount, String filter) {
        public static final Codec<Limit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Scope.CODEC.fieldOf("scope").forGetter(Limit::scope),
                Codec.LONG.fieldOf("amount").forGetter(Limit::amount),
                Codec.STRING.optionalFieldOf("filter", "").forGetter(Limit::filter)
        ).apply(i, Limit::new));
    }

    /**
     * Engine-internal form of a device node's output (see {@link DeviceSettings}); never saved.
     *
     * @param perOperation items moved per operation at most; 0 means one full stack of whatever moves (items only;
     *                     continuous kinds use a rate limit node)
     * @param interval     ticks between operations; 0 uses the sheet's interval (items only)
     * @param priority     higher runs first when several sources feed one flow
     */
    record SourceSettings(Port port, List<Limit> keep, int perOperation, int interval, int priority) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.SOURCE;
        }
    }

    /** Engine-internal form of a device node's input (see {@link DeviceSettings}); never saved. */
    record SinkSettings(Port port, List<Limit> caps) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.SINK;
        }
    }

    /** Output 0 carries what matches, output 1 what doesn't. */
    record FilterSettings(String expression) implements NodeSettings {
        public static final MapCodec<FilterSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("expression").forGetter(FilterSettings::expression)
        ).apply(i, FilterSettings::new));

        @Override
        public Kind kind() {
            return Kind.FILTER;
        }
    }

    /** Items, mB or FE per second. */
    record RateLimitSettings(long perSecond) implements NodeSettings {
        public static final MapCodec<RateLimitSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.fieldOf("per_second").forGetter(RateLimitSettings::perSecond)
        ).apply(i, RateLimitSettings::new));

        @Override
        public Kind kind() {
            return Kind.RATE_LIMIT;
        }
    }

    enum SplitMode implements LowercaseName {
        /** Rotate which output goes first, one operation at a time (items) or share evenly (continuous). */
        ROUND_ROBIN,
        /** Outputs in wire order: the first takes all it can before the next gets any. */
        PRIORITY,
        /** Shares in proportion to each output's weight (items: 3 and 1 go A, A, B, A...; continuous: split by weight). */
        WEIGHTED,
        /** The output whose device is nearest the source's device first, then the next nearest. */
        NEAREST;

        public static final Codec<SplitMode> CODEC = StringRepresentable.fromEnum(SplitMode::values);
    }

    /**
     * {@code weights}: for WEIGHTED, each branch's by the id of its wire (a missing one counts 1), so a weight stays
     * with its branch when another is removed or they are reordered.
     */
    record SplitterSettings(SplitMode mode, Map<UUID, Integer> weights) implements NodeSettings {
        public static final MapCodec<SplitterSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                SplitMode.CODEC.optionalFieldOf("mode", SplitMode.ROUND_ROBIN).forGetter(SplitterSettings::mode),
                Codec.unboundedMap(Ids.UUID_CODEC, Codec.INT).optionalFieldOf("weights", Map.of()).forGetter(SplitterSettings::weights)
        ).apply(i, SplitterSettings::new));

        public SplitterSettings(SplitMode mode) {
            this(mode, Map.of());
        }

        public int weight(UUID wire) {
            return Math.max(0, weights.getOrDefault(wire, 1));
        }

        /** The same weights under other wire ids (a blueprint's splitter as pasted into an instance). */
        public SplitterSettings renamed(java.util.function.Function<UUID, UUID> wireId) {
            Map<UUID, Integer> out = new java.util.LinkedHashMap<>();
            weights.forEach((wire, weight) -> out.put(wireId.apply(wire), weight));
            return new SplitterSettings(mode, out);
        }

        @Override
        public Kind kind() {
            return Kind.SPLITTER;
        }
    }

    /** What a signal output does while the sheet it is in is switched off. */
    enum WhileOff implements LowercaseName {
        /** Stays at its last value (the logic feeding it stops with the sheet). */
        HOLD,
        /** Goes to a set value, 0 to 15. */
        FIXED,
        /** The logic feeding it keeps working as if the sheet were on. */
        RUN;

        public static final Codec<WhileOff> CODEC = StringRepresentable.fromEnum(WhileOff::values);
    }

    /** {@code mode}, and for {@link WhileOff#FIXED} the value it goes to. */
    record OffBehavior(WhileOff mode, int value) {
        public static final OffBehavior HOLD = new OffBehavior(WhileOff.HOLD, 0);
        public static final Codec<OffBehavior> CODEC = RecordCodecBuilder.create(i -> i.group(
                WhileOff.CODEC.optionalFieldOf("mode", WhileOff.HOLD).forGetter(OffBehavior::mode),
                Codec.INT.optionalFieldOf("value", 0).forGetter(OffBehavior::value)
        ).apply(i, OffBehavior::new));
    }

    /**
     * One way into a device node: what it takes, through which faces, and when to stop. {@code whileOff}: for a
     * redstone input (the redstone given to the device), what it gives while the node's sheet is switched off.
     */
    record Input(ResourceKind resource, List<FaceKey> faces, Optional<SlotRange> slots, List<Limit> caps, OffBehavior whileOff) {
        public Input(ResourceKind resource, List<FaceKey> faces, Optional<SlotRange> slots, List<Limit> caps) {
            this(resource, faces, slots, caps, OffBehavior.HOLD);
        }

        public static final Codec<Input> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(Input::resource),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(Input::faces),
                SlotRange.CODEC.optionalFieldOf("slots").forGetter(Input::slots),
                Limit.CODEC.listOf().optionalFieldOf("caps", List.of()).forGetter(Input::caps),
                OffBehavior.CODEC.optionalFieldOf("while_off", OffBehavior.HOLD).forGetter(Input::whileOff)
        ).apply(i, Input::new));
    }

    /** One way out of a device node; the same options as a source. {@code comparator}: a redstone output reads the comparator value. */
    record Output(ResourceKind resource, List<FaceKey> faces, Optional<SlotRange> slots, List<Limit> keep, int perOperation, int interval,
            int priority, boolean comparator) {
        public Output(ResourceKind resource, List<FaceKey> faces, Optional<SlotRange> slots, List<Limit> keep, int perOperation, int interval,
                int priority) {
            this(resource, faces, slots, keep, perOperation, interval, priority, false);
        }

        public static final Codec<Output> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(Output::resource),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(Output::faces),
                SlotRange.CODEC.optionalFieldOf("slots").forGetter(Output::slots),
                Limit.CODEC.listOf().optionalFieldOf("keep", List.of()).forGetter(Output::keep),
                Codec.INT.optionalFieldOf("per_operation", 0).forGetter(Output::perOperation),
                Codec.INT.optionalFieldOf("interval", 0).forGetter(Output::interval),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(Output::priority),
                Codec.BOOL.optionalFieldOf("comparator", false).forGetter(Output::comparator)
        ).apply(i, Output::new));
    }

    /**
     * A block that is both fed and emptied, such as a machine: any number of inputs (left) and outputs (right),
     * each with its own resource, faces and rules. The engine treats each output as a source and each input as a sink.
     */
    record DeviceSettings(GlobalPos device, List<Input> inputs, List<Output> outputs, Optional<ResourceLocation> block) implements NodeSettings {
        public DeviceSettings(GlobalPos device, List<Input> inputs, List<Output> outputs) {
            this(device, inputs, outputs, Optional.empty());
        }

        public static final MapCodec<DeviceSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                GlobalPos.CODEC.fieldOf("device").forGetter(DeviceSettings::device),
                Input.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(DeviceSettings::inputs),
                Output.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(DeviceSettings::outputs),
                ResourceLocation.CODEC.optionalFieldOf("block").forGetter(DeviceSettings::block)
        ).apply(i, DeviceSettings::new));

        /** As a slot of a blueprint: {@code block} is the kind of block it takes. */
        public DeviceSettings withBlock(Optional<ResourceLocation> block) {
            return new DeviceSettings(device, inputs, outputs, block);
        }

        /**
         * This slot filled by a block: the block's position, and every face turned the binding's quarter turns (for a
         * set built facing another way). No block type: it is a plain device node now.
         */
        public DeviceSettings boundTo(Binding binding) {
            List<Input> in = inputs.stream().map(x -> new Input(x.resource(), Binding.turn(x.faces(), binding.turns()), x.slots(), x.caps(), x.whileOff())).toList();
            List<Output> out = outputs.stream().map(x -> new Output(x.resource(), Binding.turn(x.faces(), binding.turns()), x.slots(), x.keep(),
                    x.perOperation(), x.interval(), x.priority(), x.comparator())).toList();
            return new DeviceSettings(binding.device(), in, out, Optional.empty());
        }

        @Override
        public Kind kind() {
            return Kind.DEVICE;
        }
    }

    /**
     * An instance of a blueprint placed here, {@code bindings} saying which block fills each of its slots. Its pins are the
     * blueprint's Sheet input nodes (inputs, left) and Sheet output nodes (outputs, right); wires attach to them by slot.
     */
    record InstanceSettings(UUID sheet, Map<UUID, Binding> bindings) implements NodeSettings {
        public InstanceSettings(UUID sheet) {
            this(sheet, Map.of());
        }

        public static final MapCodec<InstanceSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ids.UUID_CODEC.fieldOf("sheet").forGetter(InstanceSettings::sheet),
                Codec.unboundedMap(Ids.UUID_CODEC, Binding.CODEC).optionalFieldOf("bindings", Map.of()).forGetter(InstanceSettings::bindings)
        ).apply(i, InstanceSettings::new));

        @Override
        public Kind kind() {
            return Kind.INSTANCE;
        }
    }

    /**
     * The block filling one slot of a blueprint instance, and how far the set is turned from the blueprint's (quarter turns
     * clockwise seen from above), so its faces turn with it.
     */
    record Binding(GlobalPos device, int turns) {
        public static final Codec<Binding> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("device").forGetter(Binding::device),
                Codec.INT.optionalFieldOf("turns", 0).forGetter(Binding::turns)
        ).apply(i, Binding::new));

        /** A face turned {@code turns} quarter turns clockwise (seen from above); up, down and unsided stay. */
        public static FaceKey turn(FaceKey face, int turns) {
            net.minecraft.core.Direction direction = face.direction();
            if (direction == null || direction.getAxis().isVertical()) {
                return face;
            }
            for (int i = 0; i < Math.floorMod(turns, 4); i++) {
                direction = direction.getClockWise();
            }
            return FaceKey.of(direction);
        }

        public static List<FaceKey> turn(List<FaceKey> faces, int turns) {
            return Math.floorMod(turns, 4) == 0 ? faces : faces.stream().map(f -> turn(f, turns)).toList();
        }
    }

    /**
     * A child sheet placed on its parent sheet. The child's Sheet input nodes are this node's inputs (left) and its
     * Sheet output nodes its outputs (right). A sheet is placed exactly once; its node's switch and gate switch
     * the whole sheet, everything in it included.
     */
    record SheetSettings(UUID sheet) implements NodeSettings {
        public static final MapCodec<SheetSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ids.UUID_CODEC.fieldOf("sheet").forGetter(SheetSettings::sheet)
        ).apply(i, SheetSettings::new));

        @Override
        public Kind kind() {
            return Kind.SHEET;
        }
    }

    /**
     * A pin on a sheet's edge, placed inside it: where resources or signals enter (input) or leave (output). Wires on
     * the sheet's node attach by {@code slot}, a number fixed when the pin is made, so adding or removing a pin never
     * moves the wires on the others. {@code resource} is what it carries (empty: anything, as older pins did).
     */
    record SheetPinSettings(boolean input, String name, Optional<ResourceKind> resource, int slot, OffBehavior whileOff) implements NodeSettings {
        public SheetPinSettings(boolean input, String name) {
            this(input, name, Optional.empty(), -1, OffBehavior.HOLD);
        }

        public static final MapCodec<SheetPinSettings> INPUT_CODEC = codec(true);
        public static final MapCodec<SheetPinSettings> OUTPUT_CODEC = codec(false);

        private static MapCodec<SheetPinSettings> codec(boolean input) {
            return RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("name", "").forGetter(SheetPinSettings::name),
                    ResourceKind.CODEC.optionalFieldOf("resource").forGetter(SheetPinSettings::resource),
                    Codec.INT.optionalFieldOf("slot", -1).forGetter(SheetPinSettings::slot),
                    OffBehavior.CODEC.optionalFieldOf("while_off", OffBehavior.HOLD).forGetter(SheetPinSettings::whileOff)
            ).apply(i, (name, resource, slot, whileOff) -> new SheetPinSettings(input, name, resource, slot, whileOff)));
        }

        public SheetPinSettings withSlot(int slot) {
            return new SheetPinSettings(input, name, resource, slot, whileOff);
        }

        @Override
        public Kind kind() {
            return input ? Kind.SHEET_INPUT : Kind.SHEET_OUTPUT;
        }
    }

    record KitLine(String filter, int count) {
        public static final Codec<KitLine> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("filter").forGetter(KitLine::filter),
                Codec.INT.fieldOf("count").forGetter(KitLine::count)
        ).apply(i, KitLine::new));
    }

    /** Items only: moves whole sets, all or nothing, up to {@code maxKits} sets per operation. */
    record KitSettings(List<KitLine> lines, int maxKits) implements NodeSettings {
        public static final MapCodec<KitSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                KitLine.CODEC.listOf().fieldOf("lines").forGetter(KitSettings::lines),
                Codec.INT.optionalFieldOf("max_kits", 1).forGetter(KitSettings::maxKits)
        ).apply(i, KitSettings::new));

        @Override
        public Kind kind() {
            return Kind.KIT;
        }
    }

    // ---------------------------------------------------------------- signal nodes
    //
    // Signal nodes pass integers along signal wires. Anything above 0 counts as on; nodes that answer yes or no
    // give 15 or 0, so their output can drive redstone directly.

    /**
     * Engine-internal form of a device's redstone output pin: the redstone the device gives out of its faces (any
     * face when none are chosen: the strongest), or its comparator value.
     */
    record RedstoneInSettings(GlobalPos device, List<FaceKey> faces, boolean comparator) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.REDSTONE_IN;
        }
    }

    /**
     * Engine-internal form of a device's redstone input pin: the connectors touching the chosen faces (all of them
     * when none are chosen) give the device the pin's input, 0 to 15.
     */
    record RedstoneOutSettings(GlobalPos device, List<FaceKey> faces, OffBehavior whileOff) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.REDSTONE_OUT;
        }
    }

    /** How much of a resource a device holds (items, mB or FE), counting only what matches {@code filter}. */
    /**
     * {@code output}: what it gives: the amount itself, the amount mapped onto 0 to 15 between {@code low} (0) and
     * {@code high} (15), or 0 to 15 by how full the device is, like a comparator. {@code slot}: on a blueprint, the slot
     * whose block it reads (by the slot node's id; each instance reads the block filling it), else {@code device} itself.
     */
    record LevelSettings(ResourceKind resource, GlobalPos device, List<FaceKey> faces, String filter, LevelOutput output, long low, long high,
            Optional<UUID> slot) implements NodeSettings {
        public LevelSettings(ResourceKind resource, GlobalPos device, List<FaceKey> faces, String filter) {
            this(resource, device, faces, filter, LevelOutput.AMOUNT, 0, 0, Optional.empty());
        }

        public LevelSettings(ResourceKind resource, GlobalPos device, List<FaceKey> faces, String filter, LevelOutput output, long low, long high) {
            this(resource, device, faces, filter, output, low, high, Optional.empty());
        }

        public static final MapCodec<LevelSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(LevelSettings::resource),
                GlobalPos.CODEC.fieldOf("device").forGetter(LevelSettings::device),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(LevelSettings::faces),
                Codec.STRING.optionalFieldOf("filter", "").forGetter(LevelSettings::filter),
                LevelOutput.CODEC.optionalFieldOf("output", LevelOutput.AMOUNT).forGetter(LevelSettings::output),
                Codec.LONG.optionalFieldOf("low", 0L).forGetter(LevelSettings::low),
                Codec.LONG.optionalFieldOf("high", 0L).forGetter(LevelSettings::high),
                Ids.UUID_CODEC.optionalFieldOf("slot").forGetter(LevelSettings::slot)
        ).apply(i, LevelSettings::new));

        /** The same sensor reading another device (its faces as given): a block replaced. */
        public LevelSettings reading(GlobalPos device, List<FaceKey> faces) {
            return new LevelSettings(resource, device, faces, filter, output, low, high, slot);
        }

        /** The same sensor reading a device itself, not a slot (in an instance: the block filling its slot). */
        public LevelSettings readingDevice(GlobalPos device, List<FaceKey> faces) {
            return new LevelSettings(resource, device, faces, filter, output, low, high, Optional.empty());
        }

        /** The same sensor reading a blueprint's slot (under its id), from where the slot was laid out. */
        public LevelSettings readingSlot(UUID slot, GlobalPos layout) {
            return new LevelSettings(resource, layout, faces, filter, output, low, high, Optional.of(slot));
        }

        /** The same sensor with another filter (a template renamed). */
        public LevelSettings filtered(String filter) {
            return new LevelSettings(resource, device, faces, filter, output, low, high, slot);
        }

        /**
         * What it gives for {@code amount}, or for {@code fullness} (0 to 1, and whether anything is there at all) in
         * comparator mode.
         */
        public long value(long amount, double fullness, boolean any) {
            return switch (output) {
                case AMOUNT -> amount;
                // Like vanilla: 0 at or below the low point, 15 at or above the high point, 1 to 14 between.
                case RANGE -> amount <= low ? 0 : amount >= high ? 15 : 1 + (long) Math.floor((double) (amount - low) / (high - low) * 14);
                case COMPARATOR -> (long) Math.floor(fullness * 14) + (any ? 1 : 0);
            };
        }

        @Override
        public Kind kind() {
            return Kind.LEVEL;
        }
    }

    /** What a Level sensor gives: the amount, or 0 to 15 (between two amounts, or by fullness like a comparator). */
    enum LevelOutput implements LowercaseName {
        AMOUNT, RANGE, COMPARATOR;

        public static final Codec<LevelOutput> CODEC = StringRepresentable.fromEnum(LevelOutput::values);
    }

    enum CompareOp implements LowercaseName {
        LT, LE, GT, GE, EQ, NE;

        public static final Codec<CompareOp> CODEC = StringRepresentable.fromEnum(CompareOp::values);

        public boolean test(long a, long b) {
            return switch (this) {
                case LT -> a < b;
                case LE -> a <= b;
                case GT -> a > b;
                case GE -> a >= b;
                case EQ -> a == b;
                case NE -> a != b;
            };
        }
    }

    /** Input A (0) against input B (1), or against {@code constant} when B isn't wired. */
    record CompareSettings(CompareOp op, long constant) implements NodeSettings {
        public static final MapCodec<CompareSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                CompareOp.CODEC.optionalFieldOf("op", CompareOp.GE).forGetter(CompareSettings::op),
                Codec.LONG.optionalFieldOf("constant", 1L).forGetter(CompareSettings::constant)
        ).apply(i, CompareSettings::new));

        @Override
        public Kind kind() {
            return Kind.COMPARE;
        }
    }

    enum LogicOp implements LowercaseName {
        /** All on (and at least one wire). */
        AND,
        /** Any on. */
        OR,
        /** None on: a NOT with one wire, a NOR with several. */
        NOT,
        /** An odd number on. */
        XOR,
        /** Not all on. */
        NAND,
        /** An even number on (with two wires: they match). */
        XNOR;

        public static final Codec<LogicOp> CODEC = StringRepresentable.fromEnum(LogicOp::values);

        /** The result with {@code on} of {@code all} input wires on. */
        public boolean test(int on, int all) {
            return switch (this) {
                case AND -> all > 0 && on == all;
                case OR -> on > 0;
                case NOT -> on == 0;
                case XOR -> on % 2 == 1;
                case NAND -> !(all > 0 && on == all);
                case XNOR -> on % 2 == 0;
            };
        }
    }

    /** Every wire into the input is one operand. NOT is on only while none of them is (so with several, a NOR). */
    record LogicSettings(LogicOp op) implements NodeSettings {
        public static final MapCodec<LogicSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                LogicOp.CODEC.optionalFieldOf("op", LogicOp.AND).forGetter(LogicSettings::op)
        ).apply(i, LogicSettings::new));

        @Override
        public Kind kind() {
            return Kind.LOGIC;
        }
    }

    /**
     * Gives {@code value} (0 to 15 for redstone; more to count with or compare against) while its "on" input is above 0
     * or unwired, else 0. Switched off, it gives nothing: its switch works as a lever in the editor.
     */
    record ConstantSettings(long value) implements NodeSettings {
        public static final MapCodec<ConstantSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.optionalFieldOf("value", 15L).forGetter(ConstantSettings::value)
        ).apply(i, ConstantSettings::new));

        @Override
        public Kind kind() {
            return Kind.CONSTANT;
        }
    }

    /** How bad a raised alarm is: shown yellow or red, and what beacons and the network tool react to. */
    enum AlarmSeverity implements LowercaseName {
        WARNING, CRITICAL;

        public static final Codec<AlarmSeverity> CODEC = StringRepresentable.fromEnum(AlarmSeverity::values);
    }

    /**
     * Raises an alarm while its input passes the test ({@code op} against {@code threshold}; with no test, while it is
     * above 0) and has for {@code delay} seconds; while raised gives 15 if critical, 7 a warning, so it can drive a
     * device (a siren, a lamp, a Status Beacon in Redstone mode, which shows 1 to 7 as a warning and 8 to 15 critical).
     * Unwired, it never goes off; switched off, it is muted.
     */
    record AlarmSettings(AlarmSeverity severity, String message, Optional<CompareOp> op, long threshold, int delay) implements NodeSettings {
        public static final int MESSAGE_LIMIT = 200;
        public static final MapCodec<AlarmSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                AlarmSeverity.CODEC.optionalFieldOf("severity", AlarmSeverity.WARNING).forGetter(AlarmSettings::severity),
                Codec.string(0, MESSAGE_LIMIT).optionalFieldOf("message", "").forGetter(AlarmSettings::message),
                CompareOp.CODEC.optionalFieldOf("op").forGetter(AlarmSettings::op),
                Codec.LONG.optionalFieldOf("threshold", 0L).forGetter(AlarmSettings::threshold),
                Codec.intRange(0, 86_400).optionalFieldOf("delay", 0).forGetter(AlarmSettings::delay)
        ).apply(i, AlarmSettings::new));

        /** Whether an input value sets it off (before its delay). */
        public boolean holds(long input) {
            return op.map(o -> o.test(input, threshold)).orElse(input > 0);
        }

        @Override
        public Kind kind() {
            return Kind.ALARM;
        }
    }

    /** Gives a one-tick pulse every {@code interval} ticks, while its input is on or unwired. */
    record TimerSettings(int interval) implements NodeSettings {
        public static final MapCodec<TimerSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.optionalFieldOf("interval", 20).forGetter(TimerSettings::interval)
        ).apply(i, TimerSettings::new));

        @Override
        public Kind kind() {
            return Kind.TIMER;
        }
    }

    /** On each rising edge of its input, gives 15 for {@code length} ticks. */
    record PulseSettings(int length) implements NodeSettings {
        public static final MapCodec<PulseSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.optionalFieldOf("length", 1).forGetter(PulseSettings::length)
        ).apply(i, PulseSettings::new));

        @Override
        public Kind kind() {
            return Kind.PULSE;
        }
    }

    /** Set (input 0) turns it on, reset (input 1) off; it remembers in between, even across restarts. */
    record LatchSettings() implements NodeSettings {
        public static final MapCodec<LatchSettings> CODEC = MapCodec.unit(new LatchSettings());

        @Override
        public Kind kind() {
            return Kind.LATCH;
        }
    }

    /** Counts rising edges on input 0; input 1 resets it to 0. Kept across restarts. */
    record CounterSettings() implements NodeSettings {
        public static final MapCodec<CounterSettings> CODEC = MapCodec.unit(new CounterSettings());

        @Override
        public Kind kind() {
            return Kind.COUNTER;
        }
    }

    /**
     * A switch flipped from the editor's dashboard (by players who can edit the network): gives {@code value} while on,
     * 0 while off. With {@code level}, it is set to any value from 0 to {@code value} instead. Where it stands is kept
     * across restarts, per blueprint instance. {@code icon} is an item the dashboard shows it with.
     */
    record SwitchSettings(long value, boolean level, Optional<ResourceLocation> icon) implements NodeSettings {
        public static final MapCodec<SwitchSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.optionalFieldOf("value", 15L).forGetter(SwitchSettings::value),
                Codec.BOOL.optionalFieldOf("level", false).forGetter(SwitchSettings::level),
                ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(SwitchSettings::icon)
        ).apply(i, SwitchSettings::new));

        public SwitchSettings {
            value = Math.max(1, value);
        }

        /** What it may be set to: 0 or its value, or with {@code level} anything between. */
        public boolean allows(long to) {
            return level ? to >= 0 && to <= value : to == 0 || to == value;
        }

        @Override
        public Kind kind() {
            return Kind.SWITCH;
        }
    }

    /** How a Display shows its value on the dashboard. */
    enum DisplayStyle implements LowercaseName {
        /** The number, with its unit. */
        NUMBER,
        /** A bar filling up to {@code max}. */
        GAUGE,
        /** A light: lit while above 0. */
        LIGHT;

        public static final Codec<DisplayStyle> CODEC = StringRepresentable.fromEnum(DisplayStyle::values);
    }

    /**
     * Shows what is wired into it on the editor's dashboard (the highest of its wires), and passes it on. {@code unit}
     * follows the number ("items", "%"); {@code max} is a gauge's full mark; {@code icon} is an item the dashboard shows it
     * with.
     */
    record DisplaySettings(DisplayStyle style, String unit, long max, Optional<ResourceLocation> icon) implements NodeSettings {
        public static final int UNIT_LIMIT = 16;
        public static final MapCodec<DisplaySettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                DisplayStyle.CODEC.optionalFieldOf("style", DisplayStyle.NUMBER).forGetter(DisplaySettings::style),
                Codec.string(0, UNIT_LIMIT).optionalFieldOf("unit", "").forGetter(DisplaySettings::unit),
                Codec.LONG.optionalFieldOf("max", 15L).forGetter(DisplaySettings::max),
                ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(DisplaySettings::icon)
        ).apply(i, DisplaySettings::new));

        public DisplaySettings {
            max = Math.max(1, max);
        }

        @Override
        public Kind kind() {
            return Kind.DISPLAY;
        }
    }

    /** One row of a Sequencer: a name (for the editor) and a value per step, as a hex digit (0 nothing, 1 to f). */
    record SequencerTrack(String name, String cells) {
        public static final int NAME_LIMIT = 32;
        public static final Codec<SequencerTrack> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.string(0, NAME_LIMIT).optionalFieldOf("name", "").forGetter(SequencerTrack::name),
                Codec.string(0, SequencerSettings.STEP_LIMIT).optionalFieldOf("cells", "").forGetter(SequencerTrack::cells)
        ).apply(i, SequencerTrack::new));

        /** The value at a step (0 to 15); steps past the cells are 0. */
        public int at(int step) {
            if (step < 0 || step >= cells.length()) {
                return 0;
            }
            int value = Character.digit(cells.charAt(step), 16);
            return Math.max(0, value);
        }
    }

    /**
     * Steps through {@code steps} steps, {@code stepTicks} ticks each, while its "run" input is on or unwired; "reset"
     * goes back to the first step. Output 0 is the step playing (1 on, 0 once stopped); output 1 and on are its tracks:
     * each step's value, for one tick as the step starts ({@code pulse}, so a note block plays each hit) or held for
     * the step. At the end it starts again ({@code loop}) or stops until reset.
     */
    record SequencerSettings(int steps, int stepTicks, boolean loop, boolean pulse, List<SequencerTrack> tracks) implements NodeSettings {
        public static final int STEP_LIMIT = 256;
        public static final int TRACK_LIMIT = 16;
        public static final MapCodec<SequencerSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(1, STEP_LIMIT).optionalFieldOf("steps", 16).forGetter(SequencerSettings::steps),
                Codec.intRange(1, 1200).optionalFieldOf("step_ticks", 4).forGetter(SequencerSettings::stepTicks),
                Codec.BOOL.optionalFieldOf("loop", true).forGetter(SequencerSettings::loop),
                Codec.BOOL.optionalFieldOf("pulse", true).forGetter(SequencerSettings::pulse),
                SequencerTrack.CODEC.sizeLimitedListOf(TRACK_LIMIT).optionalFieldOf("tracks", List.of()).forGetter(SequencerSettings::tracks)
        ).apply(i, SequencerSettings::new));

        @Override
        public Kind kind() {
            return Kind.SEQUENCER;
        }
    }

    /** Gives its input as it was {@code ticks} ticks ago. */
    record DelaySettings(int ticks) implements NodeSettings {
        public static final MapCodec<DelaySettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.optionalFieldOf("ticks", 1).forGetter(DelaySettings::ticks)
        ).apply(i, DelaySettings::new));

        @Override
        public Kind kind() {
            return Kind.DELAY;
        }
    }

    /**
     * Engine-internal form of a device's ME pin: the connector side touching that device face (AE2). A wire between
     * two ME pins joins their AE2 networks, 32 channels at most (8 through a normal cable), like AE2's own cables.
     */
    record MePortSettings(UUID connector, net.minecraft.core.Direction side) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.ME_PORT;
        }
    }
}
