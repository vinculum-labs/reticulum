package io.github.leadassimilator.reticulum.network;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * A block a connector reaches, keyed by its position so names survive replacing the connector.
 * Records holding no user data are dropped as soon as no connector reaches them.
 */
public final class DeviceRecord {
    public enum State implements StringRepresentable {
        ACTIVE,
        ORPHANED,
        MISSING;

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final Codec<DeviceRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("pos").forGetter(r -> r.pos),
            Codec.STRING.optionalFieldOf("name").forGetter(r -> Optional.ofNullable(r.name)),
            Codec.unboundedMap(FaceKey.CODEC, Codec.STRING).optionalFieldOf("faces", Map.of()).forGetter(r -> r.faceNames),
            ResourceLocation.CODEC.fieldOf("block").forGetter(r -> r.blockId),
            State.CODEC.optionalFieldOf("state", State.ACTIVE).forGetter(r -> r.state)
    ).apply(i, DeviceRecord::new));

    private final GlobalPos pos;
    @Nullable
    private String name;
    private final Map<FaceKey, String> faceNames = new EnumMap<>(FaceKey.class);
    private ResourceLocation blockId;
    private State state;

    public DeviceRecord(GlobalPos pos, ResourceLocation blockId) {
        this(pos, Optional.empty(), Map.of(), blockId, State.ACTIVE);
    }

    private DeviceRecord(GlobalPos pos, Optional<String> name, Map<FaceKey, String> faceNames, ResourceLocation blockId, State state) {
        this.pos = pos;
        this.name = name.orElse(null);
        this.faceNames.putAll(faceNames);
        this.blockId = blockId;
        this.state = state;
    }

    public GlobalPos pos() {
        return pos;
    }

    @Nullable
    public String name() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = blankToNull(name);
    }

    @Nullable
    public String faceName(FaceKey face) {
        return faceNames.get(face);
    }

    public Map<FaceKey, String> faceNames() {
        return new HashMap<>(faceNames);
    }

    public void setFaceName(FaceKey face, @Nullable String faceName) {
        String value = blankToNull(faceName);
        if (value == null) {
            faceNames.remove(face);
        } else {
            faceNames.put(face, value);
        }
    }

    public ResourceLocation blockId() {
        return blockId;
    }

    public void setBlockId(ResourceLocation blockId) {
        this.blockId = blockId;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    /** True when the record holds something the user made and so must not be dropped silently. */
    public boolean hasUserData() {
        return name != null || !faceNames.isEmpty();
    }

    @Nullable
    static String blankToNull(@Nullable String s) {
        return Limits.name(s);
    }
}
