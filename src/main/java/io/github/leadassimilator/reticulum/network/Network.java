package io.github.leadassimilator.reticulum.network;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;

/**
 * One network: its identity, security settings, connectors and named devices.
 * Mutations must be followed by {@link ReticulumSavedData#setDirty()}; {@link NetworkManager} does this.
 */
public final class Network {
    public static final Codec<Network> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(n -> n.id),
            Codec.STRING.fieldOf("name").forGetter(n -> n.name),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(n -> Optional.ofNullable(n.owner)),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(n -> n.ownerName),
            AccessMode.CODEC.optionalFieldOf("access", AccessMode.PRIVATE).forGetter(n -> n.accessMode),
            Codec.STRING.optionalFieldOf("password").forGetter(n -> Optional.ofNullable(n.passwordHash)),
            Member.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(n -> List.copyOf(n.members.values())),
            GlobalPos.CODEC.optionalFieldOf("controller").forGetter(n -> Optional.ofNullable(n.controller)),
            ConnectorRecord.CODEC.listOf().optionalFieldOf("connectors", List.of()).forGetter(n -> List.copyOf(n.connectors.values())),
            DeviceRecord.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(n -> List.copyOf(n.devices.values()))
    ).apply(i, Network::new));

    private final UUID id;
    private String name;
    @Nullable
    private UUID owner;
    private String ownerName;
    private AccessMode accessMode;
    @Nullable
    private String passwordHash;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    @Nullable
    private GlobalPos controller;
    private final Map<UUID, ConnectorRecord> connectors = new LinkedHashMap<>();
    private final Map<GlobalPos, DeviceRecord> devices = new LinkedHashMap<>();

    public Network(UUID id, String name, @Nullable UUID owner, String ownerName, AccessMode accessMode) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.accessMode = accessMode;
    }

    private Network(UUID id, String name, Optional<UUID> owner, String ownerName, AccessMode accessMode, Optional<String> passwordHash,
            List<Member> members, Optional<GlobalPos> controller, List<ConnectorRecord> connectors, List<DeviceRecord> devices) {
        this(id, name, owner.orElse(null), ownerName, accessMode);
        this.passwordHash = passwordHash.orElse(null);
        members.forEach(m -> this.members.put(m.id(), m));
        this.controller = controller.orElse(null);
        connectors.forEach(c -> this.connectors.put(c.id(), c));
        devices.forEach(d -> this.devices.put(d.pos(), d));
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        String value = DeviceRecord.blankToNull(name);
        if (value != null) {
            this.name = value;
        }
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public AccessMode accessMode() {
        return accessMode;
    }

    public void setAccessMode(AccessMode accessMode) {
        this.accessMode = accessMode;
    }

    @Nullable
    public String passwordHash() {
        return passwordHash;
    }

    public void setPasswordHash(@Nullable String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Map<UUID, Member> members() {
        return members;
    }

    @Nullable
    public GlobalPos controller() {
        return controller;
    }

    public void setController(@Nullable GlobalPos controller) {
        this.controller = controller;
    }

    @Nullable
    public ConnectorRecord connector(UUID connectorId) {
        return connectors.get(connectorId);
    }

    public Collection<ConnectorRecord> connectors() {
        return connectors.values();
    }

    Map<UUID, ConnectorRecord> connectorMap() {
        return connectors;
    }

    @Nullable
    public DeviceRecord device(GlobalPos pos) {
        return devices.get(pos);
    }

    public Collection<DeviceRecord> devices() {
        return devices.values();
    }

    Map<GlobalPos, DeviceRecord> deviceMap() {
        return devices;
    }

    public boolean isReached(GlobalPos device) {
        for (ConnectorRecord c : connectors.values()) {
            if (c.devices().contains(device)) {
                return true;
            }
        }
        return false;
    }
}
