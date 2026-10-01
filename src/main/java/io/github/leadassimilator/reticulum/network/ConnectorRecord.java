package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.GlobalPos;

/** A connector that belongs to a network, and the devices it currently reaches. */
public final class ConnectorRecord {
    public static final Codec<ConnectorRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(r -> r.id),
            GlobalPos.CODEC.fieldOf("pos").forGetter(r -> r.pos),
            Codec.STRING.optionalFieldOf("name").forGetter(r -> Optional.ofNullable(r.name)),
            GlobalPos.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(r -> r.devices)
    ).apply(i, ConnectorRecord::new));

    private final UUID id;
    private GlobalPos pos;
    @Nullable
    private String name;
    private final List<GlobalPos> devices = new ArrayList<>();

    public ConnectorRecord(UUID id, GlobalPos pos) {
        this(id, pos, Optional.empty(), List.of());
    }

    private ConnectorRecord(UUID id, GlobalPos pos, Optional<String> name, List<GlobalPos> devices) {
        this.id = id;
        this.pos = pos;
        this.name = name.orElse(null);
        this.devices.addAll(devices);
    }

    public UUID id() {
        return id;
    }

    public GlobalPos pos() {
        return pos;
    }

    public void setPos(GlobalPos pos) {
        this.pos = pos;
    }

    @Nullable
    public String name() {
        return name;
    }

    /** Its name, or where it is when it has none. */
    public String displayName() {
        return name != null ? name : defaultName(pos.pos());
    }

    /** What an unnamed connector is called: where it is. */
    public static String defaultName(net.minecraft.core.BlockPos pos) {
        return "Connector (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public void setName(@Nullable String name) {
        this.name = DeviceRecord.blankToNull(name);
    }

    public List<GlobalPos> devices() {
        return devices;
    }
}
