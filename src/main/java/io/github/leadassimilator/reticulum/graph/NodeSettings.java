package io.github.leadassimilator.reticulum.graph;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.network.FaceKey;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.StringRepresentable;

/** The settings of one node, one record per node kind. */
public sealed interface NodeSettings {
    Kind kind();

    enum Kind implements StringRepresentable {
        SOURCE(SourceSettings.CODEC),
        SINK(SinkSettings.CODEC),
        FILTER(FilterSettings.CODEC),
        RATE_LIMIT(RateLimitSettings.CODEC),
        SPLITTER(SplitterSettings.CODEC),
        KIT(KitSettings.CODEC);

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final MapCodec<? extends NodeSettings> codec;

        Kind(MapCodec<? extends NodeSettings> codec) {
            this.codec = codec;
        }

        public MapCodec<? extends NodeSettings> codec() {
            return codec;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    Codec<NodeSettings> CODEC = Kind.CODEC.dispatch("kind", NodeSettings::kind, Kind::codec);

    /** Which device faces a source or sink uses. An empty face list means every face that answers. */
    record Port(ResourceKind resource, GlobalPos device, List<FaceKey> faces, Optional<SlotRange> slots) {
        public static final MapCodec<Port> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ResourceKind.CODEC.fieldOf("resource").forGetter(Port::resource),
                GlobalPos.CODEC.fieldOf("device").forGetter(Port::device),
                FaceKey.CODEC.listOf().optionalFieldOf("faces", List.of()).forGetter(Port::faces),
                SlotRange.CODEC.optionalFieldOf("slots").forGetter(Port::slots)
        ).apply(i, Port::new));
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
     * @param perOperation items moved per operation at most (items only; continuous kinds use a rate limit node)
     * @param interval     ticks between operations; 0 uses the sheet's interval (items only)
     * @param priority     higher runs first when several sources feed one flow
     */
    record SourceSettings(Port port, List<Limit> keep, int perOperation, int interval, int priority) implements NodeSettings {
        public static final MapCodec<SourceSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Port.CODEC.forGetter(SourceSettings::port),
                Limit.CODEC.listOf().optionalFieldOf("keep", List.of()).forGetter(SourceSettings::keep),
                Codec.INT.optionalFieldOf("per_operation", 64).forGetter(SourceSettings::perOperation),
                Codec.INT.optionalFieldOf("interval", 0).forGetter(SourceSettings::interval),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(SourceSettings::priority)
        ).apply(i, SourceSettings::new));

        @Override
        public Kind kind() {
            return Kind.SOURCE;
        }
    }

    record SinkSettings(Port port, List<Limit> caps) implements NodeSettings {
        public static final MapCodec<SinkSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Port.CODEC.forGetter(SinkSettings::port),
                Limit.CODEC.listOf().optionalFieldOf("caps", List.of()).forGetter(SinkSettings::caps)
        ).apply(i, SinkSettings::new));

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
        PRIORITY;

        public static final Codec<SplitMode> CODEC = StringRepresentable.fromEnum(SplitMode::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record SplitterSettings(SplitMode mode) implements NodeSettings {
        public static final MapCodec<SplitterSettings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                SplitMode.CODEC.optionalFieldOf("mode", SplitMode.ROUND_ROBIN).forGetter(SplitterSettings::mode)
        ).apply(i, SplitterSettings::new));

        @Override
        public Kind kind() {
            return Kind.SPLITTER;
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
}
