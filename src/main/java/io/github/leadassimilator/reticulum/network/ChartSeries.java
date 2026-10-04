package io.github.leadassimilator.reticulum.network;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * Something a network charts beyond its totals, chosen by a player (nothing is recorded per item otherwise): how much
 * of one item, fluid or chemical its flows move, or how much a device holds.
 */
public record ChartSeries(Kind kind, Optional<ResourceLocation> id, Optional<GlobalPos> device, Optional<ResourceKind> resource) {
    public enum Kind implements LowercaseName {
        /** One item's throughput. */
        ITEM,
        FLUID,
        CHEMICAL,
        /** What one device holds of a resource (FE, mB or items), sampled each second. */
        LEVEL;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
    }

    public static final Codec<ChartSeries> CODEC = RecordCodecBuilder.create(i -> i.group(
            Kind.CODEC.fieldOf("kind").forGetter(ChartSeries::kind),
            ResourceLocation.CODEC.optionalFieldOf("id").forGetter(ChartSeries::id),
            GlobalPos.CODEC.optionalFieldOf("device").forGetter(ChartSeries::device),
            ResourceKind.CODEC.optionalFieldOf("resource").forGetter(ChartSeries::resource)
    ).apply(i, ChartSeries::new));

    /** Whether it names what it needs: an id for a throughput, a device and a resource that is held for a level. */
    public boolean valid() {
        return kind == Kind.LEVEL
                ? device.isPresent() && resource.isPresent() && resource.get() != ResourceKind.REDSTONE && resource.get() != ResourceKind.ME
                : id.isPresent();
    }

    /** What the movers report a delivery as ("item:minecraft:iron_ingot"), or for a level its own key. */
    public String key() {
        return switch (kind) {
            case ITEM -> "item:" + id.orElseThrow();
            case FLUID -> "fluid:" + id.orElseThrow();
            case CHEMICAL -> "chemical:" + id.orElseThrow();
            case LEVEL -> {
                GlobalPos at = device.orElseThrow();
                yield "level:" + resource.orElseThrow().getSerializedName() + "@" + at.dimension().location() + ":" + at.pos().getX() + ","
                        + at.pos().getY() + "," + at.pos().getZ();
            }
        };
    }

    /** The metrics series it is recorded in. */
    public UUID seriesId() {
        return idOf(key());
    }

    /** The metrics series for a delivery key, or for a resource's total ("total:energy"). */
    public static UUID idOf(String key) {
        return UUID.nameUUIDFromBytes(("chart/" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** The metrics series of everything a network's flows move of one resource. */
    public static UUID totalId(ResourceKind resource) {
        return idOf("total:" + resource.getSerializedName());
    }
}
