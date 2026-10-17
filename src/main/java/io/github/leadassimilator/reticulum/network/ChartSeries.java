package io.github.leadassimilator.reticulum.network;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.graph.ResourceKind;
import io.github.leadassimilator.reticulum.util.LowercaseName;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * Something a network charts beyond its totals, chosen by a player (nothing is recorded per item otherwise): how much
 * of one item, fluid or chemical its flows move, or how much a device holds.
 */
public record ChartSeries(Kind kind, Optional<Identifier> id, Optional<GlobalPos> device, Optional<ResourceKind> resource) {
    public enum Kind implements LowercaseName {
        /** One item's throughput. */
        ITEM,
        FLUID,
        CHEMICAL,
        /** What one device holds of a resource (FE, mB or items), sampled each second. */
        LEVEL,
        /** How much of a resource the network's flows move into one device and out of it (two series). */
        THROUGH;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
    }

    public static final Codec<ChartSeries> CODEC = RecordCodecBuilder.create(i -> i.group(
            Kind.CODEC.fieldOf("kind").forGetter(ChartSeries::kind),
            Identifier.CODEC.optionalFieldOf("id").forGetter(ChartSeries::id),
            GlobalPos.CODEC.optionalFieldOf("device").forGetter(ChartSeries::device),
            ResourceKind.CODEC.optionalFieldOf("resource").forGetter(ChartSeries::resource)
    ).apply(i, ChartSeries::new));

    /** Whether it names what it needs: an id for a throughput, a device and a resource that is held for a level. */
    public boolean valid() {
        return kind == Kind.LEVEL || kind == Kind.THROUGH
                ? device.isPresent() && resource.isPresent() && resource.get() != ResourceKind.REDSTONE && resource.get() != ResourceKind.ME
                : id.isPresent();
    }

    /** What the movers report a delivery as ("item:minecraft:iron_ingot"), or for a level its own key. */
    public String key() {
        return switch (kind) {
            case ITEM -> "item:" + id.orElseThrow();
            case FLUID -> "fluid:" + id.orElseThrow();
            case CHEMICAL -> "chemical:" + id.orElseThrow();
            case LEVEL -> "level:" + at(resource.orElseThrow(), device.orElseThrow());
            case THROUGH -> throughKey(resource.orElseThrow(), device.orElseThrow());
        };
    }

    private static String at(ResourceKind resource, GlobalPos device) {
        return resource.getSerializedName() + "@" + device.dimension().identifier() + ":" + device.pos().getX() + "," + device.pos().getY() + ","
                + device.pos().getZ();
    }

    /** A THROUGH chart's key, for a resource and a device: the movers check deliveries' ends against it. */
    public static String throughKey(ResourceKind resource, GlobalPos device) {
        return "through:" + at(resource, device);
    }

    /** A THROUGH chart's series of what goes into its device (or, {@code in} false, comes out of it). */
    public UUID seriesId(boolean in) {
        return idOf(key() + (in ? "/in" : "/out"));
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
