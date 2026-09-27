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
            String defaultName, String customName, List<String> faceNames) {
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
                },
                buf -> new SideView(Direction.STREAM_CODEC.decode(buf), buf.readEnum(SideKind.class), buf.readEnum(JointView.class),
                        buf.readBoolean(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readUtf(), buf.readUtf(),
                        buf.readList(b -> b.readUtf())));

        public String faceName(FaceKey face) {
            return face.ordinal() < faceNames.size() ? faceNames.get(face.ordinal()) : "";
        }

        public String displayName() {
            return customName.isEmpty() ? defaultName : customName;
        }
    }

    /** Connector and cable screens share this view; cables have no connector name. */
    public record BlockView(BlockPos pos, boolean connector, String networkName, String defaultName, String customName,
            List<SideView> sides, boolean canEdit) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockView> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeBoolean(v.connector);
                    buf.writeUtf(v.networkName);
                    buf.writeUtf(v.defaultName);
                    buf.writeUtf(v.customName);
                    buf.writeCollection(v.sides, (b, s) -> SideView.STREAM_CODEC.encode((RegistryFriendlyByteBuf) b, s));
                    buf.writeBoolean(v.canEdit);
                },
                buf -> new BlockView(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readList(b -> SideView.STREAM_CODEC.decode((RegistryFriendlyByteBuf) b)), buf.readBoolean()));

        public SideView side(Direction dir) {
            for (SideView s : sides) {
                if (s.side() == dir) {
                    return s;
                }
            }
            throw new IllegalArgumentException("No view for side " + dir);
        }
    }

    public record ControllerView(BlockPos pos, String networkName, String ownerName, AccessMode mode, boolean hasPassword,
            int connectors, int devices, int orphans, boolean canEdit, boolean canAdmin) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ControllerView> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.networkName);
                    buf.writeUtf(v.ownerName);
                    AccessMode.STREAM_CODEC.encode(buf, v.mode);
                    buf.writeBoolean(v.hasPassword);
                    ByteBufCodecs.VAR_INT.encode(buf, v.connectors);
                    ByteBufCodecs.VAR_INT.encode(buf, v.devices);
                    ByteBufCodecs.VAR_INT.encode(buf, v.orphans);
                    buf.writeBoolean(v.canEdit);
                    buf.writeBoolean(v.canAdmin);
                },
                buf -> new ControllerView(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), AccessMode.STREAM_CODEC.decode(buf),
                        buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), buf.readBoolean(), buf.readBoolean()));
    }
}
