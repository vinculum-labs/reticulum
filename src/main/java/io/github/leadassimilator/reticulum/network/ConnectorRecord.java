package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

/** A connector that belongs to a network, and the devices it currently reaches. */
public final class ConnectorRecord {
    public static final Codec<ConnectorRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(r -> r.id),
            GlobalPos.CODEC.fieldOf("pos").forGetter(r -> r.pos),
            Codec.STRING.optionalFieldOf("name").forGetter(r -> Optional.ofNullable(r.name)),
            GlobalPos.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(r -> r.devices),
            Codec.BOOL.optionalFieldOf("moving", false).forGetter(r -> r.moving)
    ).apply(i, ConnectorRecord::new));

    private final UUID id;
    private GlobalPos pos;
    @Nullable
    private String name;
    private final List<GlobalPos> devices = new ArrayList<>();
    /** Lifted by a piston or a contraption (Create's) and not yet put down: wherever it turns up is it, not a copy. */
    private boolean moving;

    public ConnectorRecord(UUID id, GlobalPos pos) {
        this(id, pos, Optional.empty(), List.of(), false);
    }

    private ConnectorRecord(UUID id, GlobalPos pos, Optional<String> name, List<GlobalPos> devices, boolean moving) {
        this.id = id;
        this.pos = pos;
        this.name = name.orElse(null);
        this.devices.addAll(devices);
        this.moving = moving;
    }

    public UUID id() {
        return id;
    }

    public GlobalPos pos() {
        return pos;
    }

    /** Where it is now: it has arrived (no longer moving). */
    public void setPos(GlobalPos pos) {
        this.pos = pos;
        this.moving = false;
    }

    public boolean moving() {
        return moving;
    }

    public void setMoving(boolean moving) {
        this.moving = moving;
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
    public static String defaultName(BlockPos pos) {
        return "Connector (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public void setName(@Nullable String name) {
        this.name = DeviceRecord.blankToNull(name);
    }

    public List<GlobalPos> devices() {
        return devices;
    }
}
