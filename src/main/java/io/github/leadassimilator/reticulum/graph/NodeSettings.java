package io.github.leadassimilator.reticulum.graph;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.network.Ids;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.StringRepresentable;

/** The settings of one node, one record per node kind. */
public sealed interface NodeSettings {
    Kind kind();

    enum Kind implements StringRepresentable {
        /** Engine-internal: one output of a device node. Never saved or sent. */
        SOURCE(null, false),
        /** Engine-internal: one input of a device node. Never saved or sent. */
        SINK(null, false),
        FILTER(FilterSettings.CODEC, false),
        RATE_LIMIT(RateLimitSettings.CODEC, false),
        SPLITTER(SplitterSettings.CODEC, false),
        KIT(KitSettings.CODEC, false),
        DEVICE(DeviceSettings.CODEC, false),
        SUBSHEET(SubSheetSettings.CODEC, false),
        SHEET_INPUT(SheetPinSettings.INPUT_CODEC, false),
        SHEET_OUTPUT(SheetPinSettings.OUTPUT_CODEC, false),
        /** Engine-internal: a device's redstone output pin. Never saved or sent. */
        REDSTONE_IN(null, true),
        /** Engine-internal: a device's redstone input pin. Never saved or sent. */
        REDSTONE_OUT(null, true),
        LEVEL(LevelSettings.CODEC, true),
        COMPARE(CompareSettings.CODEC, true),
        LOGIC(LogicSettings.CODEC, true),
        TIMER(TimerSettings.CODEC, true),
        PULSE(PulseSettings.CODEC, true),
        LATCH(LatchSettings.CODEC, true),
        COUNTER(CounterSettings.CODEC, true),
        DELAY(DelaySettings.CODEC, true),
        TRANSMIT(TransmitSettings.CODEC, true),
        RECEIVE(ReceiveSettings.CODEC, true),
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

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
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
    enum Scope implements StringRepresentable {
        /** Everything in the port together. */
        TOTAL,
        /** Each item or fluid type on its own. */
        PER_TYPE,
        /** Everything matching the rule's filter together. */
        FILTER;

        public static final Codec<Scope> CODEC = StringRepresentable.fromEnum(Scope::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
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

    enum SplitMode implements StringRepresentable {
        /** Rotate which output goes first, one operation at a time (items) or share evenly (continuous). */
        ROUND_ROBIN,
        /** Outputs in wire order: the first takes all it can before the next gets any. */
        PRIORITY,
        /** Shares in proportion to each output's weight (items: 3 and 1 go A, A, B, A...; continuous: split by weight). */
        WEIGHTED,
        /** The output whose device is nearest the source's device first, then the next nearest. */
        NEAREST;

        public static final Codec<SplitMode> CODEC = StringRepresentable.fromEnum(SplitMode::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code weights}: for WEIGHTED, one per output in wire order (missing ones count 1). */
    record SplitterSettings(SplitMode mode, List<Integer> weights) implements NodeSettings {
        public static final MapCodec<SplitterSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                SplitMode.CODEC.optionalFieldOf("mode", SplitMode.ROUND_ROBIN).forGetter(SplitterSettings::mode),
                Codec.INT.listOf().optionalFieldOf("weights", List.of()).forGetter(SplitterSettings::weights)
        ).apply(i, SplitterSettings::new));

        public SplitterSettings(SplitMode mode) {
            this(mode, List.of());
        }

        public int weight(int output) {
            return output < weights.size() ? Math.max(0, weights.get(output)) : 1;
        }

        @Override
        public Kind kind() {
            return Kind.SPLITTER;
        }
    }

    /** One way into a device node: what it takes, through which faces, and when to stop. */
    record Input(ResourceKind resource, List<FaceKey> faces, Optional<SlotRange> slots, List<Limit> caps) {
        public static final Codec<Input> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(Input::resource),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(Input::faces),
                SlotRange.CODEC.optionalFieldOf("slots").forGetter(Input::slots),
                Limit.CODEC.listOf().optionalFieldOf("caps", List.of()).forGetter(Input::caps)
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
    record DeviceSettings(GlobalPos device, List<Input> inputs, List<Output> outputs) implements NodeSettings {
        public static final MapCodec<DeviceSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                GlobalPos.CODEC.fieldOf("device").forGetter(DeviceSettings::device),
                Input.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(DeviceSettings::inputs),
                Output.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(DeviceSettings::outputs)
        ).apply(i, DeviceSettings::new));

        @Override
        public Kind kind() {
            return Kind.DEVICE;
        }
    }

    /**
     * A copy of another sheet (one marked as a sub-sheet) placed here. Its pins are that sheet's Sheet input nodes
     * (inputs, left) and Sheet output nodes (outputs, right), each ordered top to bottom as laid out in it.
     */
    record SubSheetSettings(UUID sheet) implements NodeSettings {
        public static final MapCodec<SubSheetSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ids.UUID_CODEC.fieldOf("sheet").forGetter(SubSheetSettings::sheet)
        ).apply(i, SubSheetSettings::new));

        @Override
        public Kind kind() {
            return Kind.SUBSHEET;
        }
    }

    /** A named pin of a sub-sheet, placed inside it: where resources enter (input) or leave (output). */
    record SheetPinSettings(boolean input, String name) implements NodeSettings {
        public static final MapCodec<SheetPinSettings> INPUT_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(SheetPinSettings::name)
        ).apply(i, name -> new SheetPinSettings(true, name)));
        public static final MapCodec<SheetPinSettings> OUTPUT_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(SheetPinSettings::name)
        ).apply(i, name -> new SheetPinSettings(false, name)));

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
    record RedstoneOutSettings(GlobalPos device, List<FaceKey> faces) implements NodeSettings {
        @Override
        public Kind kind() {
            return Kind.REDSTONE_OUT;
        }
    }

    /** How much of a resource a device holds (items, mB or FE), counting only what matches {@code filter}. */
    record LevelSettings(ResourceKind resource, GlobalPos device, List<FaceKey> faces, String filter) implements NodeSettings {
        public static final MapCodec<LevelSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(LevelSettings::resource),
                GlobalPos.CODEC.fieldOf("device").forGetter(LevelSettings::device),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(LevelSettings::faces),
                Codec.STRING.optionalFieldOf("filter", "").forGetter(LevelSettings::filter)
        ).apply(i, LevelSettings::new));

        @Override
        public Kind kind() {
            return Kind.LEVEL;
        }
    }

    enum CompareOp implements StringRepresentable {
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

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
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

    enum LogicOp implements StringRepresentable {
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

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
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

    /** Transmitter: sends its input as a named network signal that any sheet (or redstone condition) can receive. */
    record TransmitSettings(String name) implements NodeSettings {
        public static final MapCodec<TransmitSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(TransmitSettings::name)
        ).apply(i, TransmitSettings::new));

        @Override
        public Kind kind() {
            return Kind.TRANSMIT;
        }
    }

    /** Receiver: reads a named network signal (as transmitted on the previous tick). */
    record ReceiveSettings(String name) implements NodeSettings {
        public static final MapCodec<ReceiveSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(ReceiveSettings::name)
        ).apply(i, ReceiveSettings::new));

        @Override
        public Kind kind() {
            return Kind.RECEIVE;
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
