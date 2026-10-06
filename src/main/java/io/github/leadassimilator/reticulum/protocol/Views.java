package io.github.leadassimilator.reticulum.protocol;

import java.util.List;

import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** Read-only snapshots the server sends to the in-game quick screens. */
public final class Views {
    private Views() {
    }

    public enum SideKind {
        NONE,
        /** A neighboring cable, connector or controller. */
        JOINT,
        /** A device reached (or switchable) through this connector side. */
        DEVICE
    }

    public enum JointView {
        CONNECTED,
        DISCONNECTED,
        /** The two sides belong to different networks. */
        CONFLICT
    }

    /**
     * One side of a cable or connector. For devices, {@code faceNames} holds one entry per {@link FaceKey}
     * (empty string = no custom name).
     */
    public record SideView(Direction side, SideKind kind, JointView joint, boolean enabled, ItemStack icon,
            String defaultName, String customName, List<String> faceNames, int identified) {
        /** Bit in {@code identified} for the whole device; the other bits are faces, by {@link FaceKey} ordinal. */
        public static final int DEVICE_BIT = 1 << 7;

        public static final StreamCodec<RegistryFriendlyByteBuf, SideView> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    Direction.STREAM_CODEC.encode(buf, v.side);
                    buf.writeEnum(v.kind);
                    buf.writeEnum(v.joint);
                    buf.writeBoolean(v.enabled);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, v.icon);
                    buf.writeUtf(v.defaultName);
                    buf.writeUtf(v.customName);
                    buf.writeCollection(v.faceNames, (b, s) -> b.writeUtf(s));
                    buf.writeVarInt(v.identified);
                },
                buf -> new SideView(Direction.STREAM_CODEC.decode(buf), buf.readEnum(SideKind.class), buf.readEnum(JointView.class),
                        buf.readBoolean(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readUtf(), buf.readUtf(),
                        buf.readList(b -> b.readUtf()), buf.readVarInt()));

        public boolean deviceIdentified() {
            return (identified & DEVICE_BIT) != 0;
        }

        public boolean faceIdentified(FaceKey face) {
            return (identified & (1 << face.ordinal())) != 0;
        }

        public String faceName(FaceKey face) {
            return face.ordinal() < faceNames.size() ? faceNames.get(face.ordinal()) : "";
        }

        public String displayName() {
            return customName.isEmpty() ? defaultName : customName;
        }
    }

    /** Connector and cable screens share this view; cables have no connector name. */
    /** {@code bindable}: a connector no cable joins, so its network is picked in its screen. */
    public record BlockView(BlockPos pos, boolean connector, String networkName, String defaultName, String customName,
            List<SideView> sides, boolean canEdit, java.util.Optional<net.minecraft.network.chat.Component> pickHint, boolean identified, boolean canUse) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockView> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeBoolean(v.connector);
                    buf.writeUtf(v.networkName);
                    buf.writeUtf(v.defaultName);
                    buf.writeUtf(v.customName);
                    buf.writeCollection(v.sides, (b, s) -> SideView.STREAM_CODEC.encode((RegistryFriendlyByteBuf) b, s));
                    buf.writeBoolean(v.canEdit);
                    buf.writeOptional(v.pickHint, (b, c) -> net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC.encode((RegistryFriendlyByteBuf) b, c));
                    buf.writeBoolean(v.identified);
                    buf.writeBoolean(v.canUse);
                },
                buf -> new BlockView(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readList(b -> SideView.STREAM_CODEC.decode((RegistryFriendlyByteBuf) b)), buf.readBoolean(),
                        buf.readOptional(b -> net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC.decode((RegistryFriendlyByteBuf) b)),
                        buf.readBoolean(), buf.readBoolean()));

        public SideView side(Direction dir) {
            for (SideView s : sides) {
                if (s.side() == dir) {
                    return s;
                }
            }
            throw new IllegalArgumentException("No view for side " + dir);
        }
    }

    /** One sheet in the controller screen's list. */
    /**
     * A sheet in the controller screen's list, which runs down the tree: {@code depth} below the root (0 for the
     * root's own sheets), {@code enabled} its own switch, {@code parentOff} a sheet above it off.
     */
    public record SheetEntry(java.util.UUID id, String name, int depth, boolean enabled, boolean parentOff, boolean gateOff, int errors) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SheetEntry> STREAM_CODEC = net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, SheetEntry::id,
                ByteBufCodecs.STRING_UTF8, SheetEntry::name,
                ByteBufCodecs.VAR_INT, SheetEntry::depth,
                ByteBufCodecs.BOOL, SheetEntry::enabled,
                ByteBufCodecs.BOOL, SheetEntry::parentOff,
                ByteBufCodecs.BOOL, SheetEntry::gateOff,
                ByteBufCodecs.VAR_INT, SheetEntry::errors,
                SheetEntry::new);
    }

    /**
     * {@code running}: the network ticks now. {@code redstoneMode}: what redstone at the controller does. {@code overflow}:
     * a line per thing in the overflow buffer.
     */
    public record ControllerView(BlockPos pos, java.util.UUID networkId, String networkName, String ownerName, AccessMode mode, boolean hasPassword,
            int connectors, int devices, int orphans, boolean canEdit, boolean canAdmin, int antenna,
            boolean enabled, boolean running, io.github.leadassimilator.reticulum.network.Switches.Redstone redstoneMode, List<SheetEntry> sheets,
            List<String> overflow, String power) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ControllerView> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUUID(v.networkId);
                    buf.writeUtf(v.networkName);
                    buf.writeUtf(v.ownerName);
                    AccessMode.STREAM_CODEC.encode(buf, v.mode);
                    buf.writeBoolean(v.hasPassword);
                    ByteBufCodecs.VAR_INT.encode(buf, v.connectors);
                    ByteBufCodecs.VAR_INT.encode(buf, v.devices);
                    ByteBufCodecs.VAR_INT.encode(buf, v.orphans);
                    buf.writeBoolean(v.canEdit);
                    buf.writeBoolean(v.canAdmin);
                    ByteBufCodecs.VAR_INT.encode(buf, v.antenna);
                    buf.writeBoolean(v.enabled);
                    buf.writeBoolean(v.running);
                    buf.writeEnum(v.redstoneMode);
                    buf.writeCollection(v.sheets, (b, s) -> SheetEntry.STREAM_CODEC.encode((RegistryFriendlyByteBuf) b, s));
                    buf.writeCollection(v.overflow, (b, s) -> b.writeUtf(s));
                    buf.writeUtf(v.power);
                },
                buf -> new ControllerView(buf.readBlockPos(), buf.readUUID(), buf.readUtf(), buf.readUtf(), AccessMode.STREAM_CODEC.decode(buf),
                        buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), buf.readBoolean(), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf),
                        buf.readBoolean(), buf.readBoolean(), buf.readEnum(io.github.leadassimilator.reticulum.network.Switches.Redstone.class),
                        buf.readList(b -> SheetEntry.STREAM_CODEC.decode((RegistryFriendlyByteBuf) b)), buf.readList(b -> b.readUtf()),
                        buf.readUtf()));
    }
}
