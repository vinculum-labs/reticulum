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
            // No default object here: one made once would be shared by every network saved without its own (switch
            // one off and all of them went off). Each gets a fresh one in the constructor instead.
            Switches.CODEC.optionalFieldOf("switches").forGetter(n -> Optional.of(n.switches)),
            Sheet.CODEC.listOf().optionalFieldOf("sheets", List.of()).forGetter(n -> List.copyOf(n.sheets)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("filters", Map.of()).forGetter(n -> n.filters),
            OverflowBuffer.CODEC.optionalFieldOf("overflow").forGetter(n -> Optional.of(n.overflow)),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("signal_memory", Map.of()).forGetter(n -> n.signalMemory),
            Ids.lenientList(Identify.CODEC, "identify").optionalFieldOf("identified", List.of()).forGetter(n -> List.copyOf(n.identified))
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
    private Switches switches = new Switches();
    private final List<Sheet> sheets = new ArrayList<>();
    /** Named filter templates, referenced from filter expressions as {@code @name}. */
    private final Map<String, String> filters = new LinkedHashMap<>();
    /** Resources that could be neither delivered nor returned; retried first on later operations. */
    private OverflowBuffer overflow = new OverflowBuffer();
    /** What latches and counters remember (by node id), so they survive restarts. */
    private final Map<String, Long> signalMemory = new LinkedHashMap<>();
    /** What is lit up to be found, shared with everyone who can view the network. */
    private final List<Identify> identified = new ArrayList<>();
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
            Optional<Switches> switches, List<Sheet> sheets, Map<String, String> filters, Optional<OverflowBuffer> overflow, Map<String, Long> signalMemory,
            List<Identify> identified) {
        this(id, name, owner.orElse(null), ownerName, accessMode);
        this.passwordHash = passwordHash.orElse(null);
        members.forEach(m -> this.members.put(m.id(), m));
        this.controller = controller.orElse(null);
        connectors.forEach(c -> this.connectors.put(c.id(), c));
        devices.forEach(d -> this.devices.put(d.pos(), d));
        this.switches = switches.orElseGet(Switches::new);
        this.sheets.addAll(sheets);
        this.filters.putAll(filters);
        this.overflow = overflow.orElseGet(OverflowBuffer::new);
        this.signalMemory.putAll(signalMemory);
        this.identified.addAll(identified);
        ensureTree();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Renames the network, and its root sheet with it: the top of the tree goes by the network's name. */
    public void setName(String name) {
        String value = DeviceRecord.blankToNull(name);
        if (value != null) {
            this.name = value;
            for (Sheet sheet : sheets) {
                if (sheet.root()) {
                    sheet.setName(value);
                }
            }
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

    public Switches switches() {
        return switches;
    }

    public List<Identify> identified() {
        return identified;
    }

    public Map<String, Long> signalMemory() {
        return signalMemory;
    }

    public boolean enabled() {
        return switches.enabled();
    }

    public void setEnabled(boolean enabled) {
        switches.setEnabled(enabled);
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

    // ---------------------------------------------------------------- the sheet tree

    /** The root sheet: the top of the tree. There is always one once {@link #ensureTree} has run. */
    public Sheet root() {
        for (Sheet sheet : sheets) {
            if (sheet.root()) {
                return sheet;
            }
        }
        ensureTree();
        return root();
    }

    /** Where a sheet sits: its parent sheet and its node there; null for the root, blueprints and unplaced sheets. */
    public record Placement(Sheet parent, io.github.leadassimilator.reticulum.graph.Node node) {
    }

    /** Every placed sheet's placement, by sheet id: the sheet nodes on every sheet but blueprints. */
    public Map<UUID, Placement> placements() {
        Map<UUID, Placement> out = new LinkedHashMap<>();
        for (Sheet sheet : sheets) {
            if (sheet.blueprint()) {
                continue;
            }
            for (io.github.leadassimilator.reticulum.graph.Node node : sheet.nodes()) {
                if (node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.SheetSettings child) {
                    out.putIfAbsent(child.sheet(), new Placement(sheet, node));
                }
            }
        }
        return out;
    }

    @Nullable
    public Placement placement(UUID sheet) {
        return placements().get(sheet);
    }

    /** How many sheets {@code sheet} sits inside (0 for the root, or a sheet not in the tree). */
    public int depth(Sheet sheet) {
        Map<UUID, Placement> placements = placements();
        int depth = 0;
        for (Placement at = placements.get(sheet.id()); at != null && depth <= Limits.SHEET_DEPTH * 2; at = placements.get(at.parent().id())) {
            depth++;
        }
        return depth;
    }

    /** How many levels of sheets are inside {@code sheet} (0 when none). */
    public int height(Sheet sheet) {
        int deepest = depth(sheet);
        for (Sheet inside : descendants(sheet)) {
            deepest = Math.max(deepest, depth(inside));
        }
        return deepest - depth(sheet);
    }

    /** The sheets inside {@code sheet}, all the way down (not {@code sheet} itself). */
    public List<Sheet> descendants(Sheet sheet) {
        Map<UUID, Placement> placements = placements();
        List<Sheet> out = new ArrayList<>();
        java.util.ArrayDeque<UUID> todo = new java.util.ArrayDeque<>(List.of(sheet.id()));
        while (!todo.isEmpty()) {
            UUID parent = todo.poll();
            for (Map.Entry<UUID, Placement> entry : placements.entrySet()) {
                Sheet child = sheet(entry.getKey());
                if (entry.getValue().parent().id().equals(parent) && child != null && !out.contains(child)) {
                    out.add(child);
                    todo.add(child.id());
                }
            }
        }
        return out;
    }

    /** A sheet's names from the root down ("Smelting › Ores"), the root left out; just the name when unplaced. */
    public String path(Sheet sheet) {
        Map<UUID, Placement> placements = placements();
        List<String> names = new ArrayList<>();
        Sheet at = sheet;
        for (int depth = 0; at != null && !at.root() && depth < 64; depth++) {
            names.add(0, at.name());
            Placement placement = placements.get(at.id());
            at = placement == null ? null : placement.parent();
        }
        return String.join(" \u203a ", names);
    }

    /**
     * Whether a sheet may run as far as switches go: it is in the tree (not a blueprint, not unplaced), and its sheet
     * node and every sheet node above it are on. The root runs whenever the network does.
     */
    public boolean runs(Sheet sheet) {
        if (sheet.blueprint()) {
            return false;
        }
        Map<UUID, Placement> placements = placements();
        Sheet at = sheet;
        for (int depth = 0; !at.root(); depth++) {
            Placement placement = placements.get(at.id());
            if (placement == null || depth > 64 || !placement.node().enabled()) {
                return false;
            }
            at = placement.parent();
        }
        return true;
    }

    /**
     * Puts the tree right, and says whether it changed anything: a root sheet exists (made when missing), every sheet
     * but the root and blueprints is placed exactly once, sheet nodes that name a missing sheet, the root, a
     * blueprint, an already placed sheet or one of their own ancestors are dropped, sheets placed nowhere go into the
     * root, and every sheet pin has a slot. Run after loading, after edits and before compiling.
     */
    public boolean ensureTree() {
        boolean changed = false;
        Sheet root = null;
        for (Sheet sheet : sheets) {
            if (sheet.root() && (root != null || sheet.blueprint())) {
                sheet.setRoot(false);
                changed = true;
            } else if (sheet.root()) {
                root = sheet;
            }
        }
        if (root == null) {
            root = new Sheet(UUID.randomUUID(), name != null ? name : "Network");
            root.setRoot(true);
            sheets.add(0, root);
            changed = true;
        }
        // The root goes by the network's name (renaming either renames both).
        String rootName = Limits.name(name);
        if (rootName != null && !root.name().equals(rootName)) {
            root.setName(rootName);
            changed = true;
        }
        // Sheet nodes: one per sheet, only for sheets that can be placed, never on a blueprint.
        java.util.Set<UUID> placed = new java.util.HashSet<>();
        for (Sheet sheet : sheets) {
            for (io.github.leadassimilator.reticulum.graph.Node node : sheet.nodes()) {
                if (!(node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.SheetSettings child)) {
                    continue;
                }
                Sheet target = sheet(child.sheet());
                if (sheet.blueprint() || target == null || target.root() || target.blueprint() || target == sheet || !placed.add(target.id())) {
                    sheet.remove(node.id());
                    changed = true;
                }
            }
        }
        // No loops: a sheet placed inside itself (directly or further down) comes out to the root. One out breaks the
        // loop, so the walk forgets it at once and the others in the loop stay where they are.
        Map<UUID, Placement> placements = new LinkedHashMap<>(placements());
        for (Map.Entry<UUID, Placement> entry : List.copyOf(placements.entrySet())) {
            Sheet at = entry.getValue().parent();
            for (int depth = 0; at != null && !at.root(); depth++) {
                if (at.id().equals(entry.getKey()) || depth > 64) {
                    entry.getValue().parent().remove(entry.getValue().node().id());
                    placed.remove(entry.getKey());
                    placements.remove(entry.getKey());
                    changed = true;
                    break;
                }
                Placement up = placements.get(at.id());
                at = up == null ? null : up.parent();
            }
        }
        int added = 0;
        for (Sheet sheet : List.copyOf(sheets)) {
            if (!sheet.root() && !sheet.blueprint() && !placed.contains(sheet.id())) {
                float x = 40 + (root.nodes().size() + added) % 6 * 220;
                float y = 40 + (root.nodes().size() + added) / 6 * 140;
                root.add(io.github.leadassimilator.reticulum.graph.Node.of(new io.github.leadassimilator.reticulum.graph.NodeSettings.SheetSettings(
                        sheet.id())).at(x, y));
                added++;
                changed = true;
            }
        }
        for (Sheet sheet : sheets) {
            changed |= sheet.assignPinSlots();
        }
        if (changed) {
            bump();
        }
        return changed;
    }

    /**
     * Switches a sheet on or off: its sheet node's switch, or the network's for the root. Returns whether it applied
     * (an unplaced sheet has no switch).
     */
    public boolean setSheetEnabled(Sheet sheet, boolean enabled) {
        if (sheet.root()) {
            setEnabled(enabled);
            return true;
        }
        Placement placement = placement(sheet.id());
        if (placement == null) {
            return false;
        }
        placement.parent().replace(placement.node().withEnabled(enabled));
        return true;
    }

    /**
     * Where a device lives: the sheet with its device node, or with the blueprint copy one of whose slots it fills (in
     * the tree; blueprints themselves don't count), and that node. Null when it has none. {@code except} is a node to
     * leave out (the one being edited).
     */
    @Nullable
    public Home home(GlobalPos device, @Nullable UUID except) {
        for (Sheet sheet : sheets) {
            if (sheet.blueprint()) {
                continue;
            }
            for (io.github.leadassimilator.reticulum.graph.Node node : sheet.nodes()) {
                if (node.id().equals(except)) {
                    continue;
                }
                if (node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.DeviceSettings settings
                        && settings.device().equals(device)) {
                    return new Home(sheet, node);
                }
                if (node.settings() instanceof io.github.leadassimilator.reticulum.graph.NodeSettings.CopySettings copy) {
                    for (var binding : io.github.leadassimilator.reticulum.graph.Blueprints.liveBindings(sheet(copy.sheet()), copy).values()) {
                        if (binding.device().equals(device)) {
                            return new Home(sheet, node);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The sheet a device lives in, and its device node there. */
    public record Home(Sheet sheet, io.github.leadassimilator.reticulum.graph.Node node) {
    }

    public int revision() {
        return revision;
    }

    public void bump() {
        revision++;
    }

    /** What a batch of edits can change, as it is now: {@link Checkpoint#restore} puts it back. */
    public Checkpoint checkpoint() {
        Map<Sheet, Sheet> saved = new LinkedHashMap<>();
        for (Sheet sheet : sheets) {
            saved.put(sheet, sheet.saved());
        }
        Map<DeviceRecord, DeviceNames> names = new LinkedHashMap<>();
        for (DeviceRecord device : devices.values()) {
            names.put(device, new DeviceNames(device.name(), device.faceNames()));
        }
        return new Checkpoint(name, saved, new LinkedHashMap<>(filters), new LinkedHashMap<>(signalMemory), switches.enabled(), switches.redstone(),
                List.copyOf(identified), names);
    }

    /** A device's name and face names, as a checkpoint keeps them. */
    private record DeviceNames(@Nullable String name, Map<FaceKey, String> faces) {
    }

    /**
     * A network's sheets, filter templates, latch and counter memory, name, switches, identifies and device names at one
     * moment. An edit batch is all or nothing: when one of its edits is refused, the ones before it are undone with
     * this. (Edits changing anything else, which device records there are, access and members, come on their own.)
     */
    public final class Checkpoint {
        private final String name;
        private final Map<Sheet, Sheet> sheets;
        private final Map<String, String> filters;
        private final Map<String, Long> signalMemory;
        private final boolean enabled;
        private final Switches.Redstone redstone;
        private final List<Identify> identified;
        private final Map<DeviceRecord, DeviceNames> names;

        private Checkpoint(String name, Map<Sheet, Sheet> sheets, Map<String, String> filters, Map<String, Long> signalMemory, boolean enabled,
                Switches.Redstone redstone, List<Identify> identified, Map<DeviceRecord, DeviceNames> names) {
            this.name = name;
            this.sheets = sheets;
            this.filters = filters;
            this.signalMemory = signalMemory;
            this.enabled = enabled;
            this.redstone = redstone;
            this.identified = identified;
            this.names = names;
        }

        /** Puts the network back as it was (the same sheet objects, in their order; sheets made since are dropped). */
        public void restore() {
            Network.this.name = name;
            Network.this.sheets.clear();
            for (Map.Entry<Sheet, Sheet> e : sheets.entrySet()) {
                e.getKey().restore(e.getValue());
                Network.this.sheets.add(e.getKey());
            }
            Network.this.filters.clear();
            Network.this.filters.putAll(filters);
            Network.this.signalMemory.clear();
            Network.this.signalMemory.putAll(signalMemory);
            switches.setEnabled(enabled);
            switches.setRedstone(redstone);
            Network.this.identified.clear();
            Network.this.identified.addAll(identified);
            names.forEach((device, saved) -> {
                device.setName(saved.name());
                for (FaceKey face : FaceKey.values()) {
                    device.setFaceName(face, saved.faces().get(face));
                }
            });
            bump();
        }
    }
}
