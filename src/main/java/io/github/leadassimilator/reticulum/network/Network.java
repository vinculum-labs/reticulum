package io.github.leadassimilator.reticulum.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.leadassimilator.reticulum.graph.Sheet;
import io.github.leadassimilator.reticulum.security.AccessMode;
import io.github.leadassimilator.reticulum.security.Member;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * One network: its identity, security settings, connectors and named devices.
 * Mutations must be followed by {@link NetworkManager#changed}, which saves and notifies web UI subscribers.
 */
public final class Network {
    public static final Codec<Network> CODEC = RecordCodecBuilder.create(i -> i.group(
            Ids.UUID_CODEC.fieldOf("id").forGetter(n -> n.id),
            Codec.STRING.fieldOf("name").forGetter(n -> n.name),
            Ids.UUID_CODEC.optionalFieldOf("owner").forGetter(n -> Optional.ofNullable(n.owner)),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(n -> n.ownerName),
            AccessMode.CODEC.optionalFieldOf("access", AccessMode.PRIVATE).forGetter(n -> n.accessMode),
            Codec.STRING.optionalFieldOf("password").forGetter(n -> Optional.ofNullable(n.passwordHash)),
            Member.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(n -> List.copyOf(n.members.values())),
            GlobalPos.CODEC.optionalFieldOf("controller").forGetter(n -> Optional.ofNullable(n.controller)),
            ConnectorRecord.CODEC.listOf().optionalFieldOf("connectors", List.of()).forGetter(n -> List.copyOf(n.connectors.values())),
            DeviceRecord.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(n -> List.copyOf(n.devices.values())),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(n -> n.enabled),
            Sheet.CODEC.listOf().optionalFieldOf("sheets", List.of()).forGetter(n -> List.copyOf(n.sheets)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("filters", Map.of()).forGetter(n -> n.filters),
            OverflowBuffer.CODEC.optionalFieldOf("overflow", new OverflowBuffer()).forGetter(n -> n.overflow),
            Codec.STRING.listOf().optionalFieldOf("disabled_folders", List.of()).forGetter(n -> List.copyOf(n.disabledFolders))
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
    private boolean enabled = true;
    private final List<Sheet> sheets = new ArrayList<>();
    /** Named filter templates, referenced from filter expressions as {@code @name}. */
    private final Map<String, String> filters = new LinkedHashMap<>();
    /** Resources that could be neither delivered nor returned; retried first on later operations. */
    private OverflowBuffer overflow = new OverflowBuffer();
    /** Folders switched off: sheets in them, or in folders under them, don't run. */
    private final java.util.Set<String> disabledFolders = new java.util.LinkedHashSet<>();
    /** Bumped on every change; not saved. The web protocol sends a new snapshot when it moves. */
    private int revision;

    public Network(UUID id, String name, @Nullable UUID owner, String ownerName, AccessMode accessMode) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.accessMode = accessMode;
    }

    private Network(UUID id, String name, Optional<UUID> owner, String ownerName, AccessMode accessMode, Optional<String> passwordHash,
            List<Member> members, Optional<GlobalPos> controller, List<ConnectorRecord> connectors, List<DeviceRecord> devices,
            boolean enabled, List<Sheet> sheets, Map<String, String> filters, OverflowBuffer overflow, List<String> disabledFolders) {
        this(id, name, owner.orElse(null), ownerName, accessMode);
        this.passwordHash = passwordHash.orElse(null);
        members.forEach(m -> this.members.put(m.id(), m));
        this.controller = controller.orElse(null);
        connectors.forEach(c -> this.connectors.put(c.id(), c));
        devices.forEach(d -> this.devices.put(d.pos(), d));
        this.enabled = enabled;
        this.sheets.addAll(sheets);
        this.filters.putAll(filters);
        this.overflow = overflow;
        this.disabledFolders.addAll(disabledFolders);
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

    /** Transfers ownership. A network without an owner is open to everyone whatever its access mode. */
    public void setOwner(@Nullable UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
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

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Sheet> sheets() {
        return sheets;
    }

    @Nullable
    public Sheet sheet(UUID id) {
        for (Sheet sheet : sheets) {
            if (sheet.id().equals(id)) {
                return sheet;
            }
        }
        return null;
    }

    @Nullable
    public Sheet sheetNamed(String name) {
        for (Sheet sheet : sheets) {
            if (sheet.name().equalsIgnoreCase(name)) {
                return sheet;
            }
        }
        return null;
    }

    public Map<String, String> filters() {
        return filters;
    }

    public OverflowBuffer overflow() {
        return overflow;
    }

    public List<ItemStack> overflowItems() {
        return overflow.items();
    }

    public List<FluidStack> overflowFluids() {
        return overflow.fluids();
    }

    public java.util.Set<String> disabledFolders() {
        return disabledFolders;
    }

    /** Whether a sheet may run: it is on, isn't a sub-sheet, and neither its folder nor any folder above it is off. */
    public boolean runs(Sheet sheet) {
        if (!sheet.enabled() || sheet.subsheet()) {
            return false;
        }
        String folder = sheet.folder();
        while (!folder.isEmpty()) {
            if (disabledFolders.contains(folder)) {
                return false;
            }
            int slash = folder.lastIndexOf('/');
            folder = slash < 0 ? "" : folder.substring(0, slash);
        }
        return true;
    }

    public int revision() {
        return revision;
    }

    public void bump() {
        revision++;
    }
}
