package io.github.leadassimilator.reticulum.protocol;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Packets for the in-game quick screens. The web UI protocol (phase 3) is separate. */
public final class Payloads {
    public static final String VERSION = "1";

    private Payloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, name));
    }

    // ---------------------------------------------------------------- server to client

    /** {@code refresh}: new contents for the screen if it is still open (after a change made in it); ignored otherwise. */
    public record OpenBlockScreen(Views.BlockView view, boolean refresh) implements CustomPacketPayload {
        public static final Type<OpenBlockScreen> TYPE = payloadType("open_block_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBlockScreen> STREAM_CODEC = StreamCodec.composite(
                Views.BlockView.STREAM_CODEC, OpenBlockScreen::view, ByteBufCodecs.BOOL, OpenBlockScreen::refresh, OpenBlockScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** {@code refresh}: as for {@link OpenBlockScreen}. */
    public record OpenControllerScreen(Views.ControllerView view, boolean refresh) implements CustomPacketPayload {
        public static final Type<OpenControllerScreen> TYPE = payloadType("open_controller_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenControllerScreen> STREAM_CODEC = StreamCodec.composite(
                Views.ControllerView.STREAM_CODEC, OpenControllerScreen::view, ByteBufCodecs.BOOL, OpenControllerScreen::refresh, OpenControllerScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Look-at lines for the built-in HUD, used when neither Jade nor The One Probe is installed. */
    public record LookResponse(BlockPos pos, java.util.List<net.minecraft.network.chat.Component> lines) implements CustomPacketPayload {
        public static final Type<LookResponse> TYPE = payloadType("look_response");
        public static final StreamCodec<RegistryFriendlyByteBuf, LookResponse> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LookResponse::pos,
                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(16)), LookResponse::lines,
                LookResponse::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- client to server

    public record LookRequest(BlockPos pos, Direction face) implements CustomPacketPayload {
        public static final Type<LookRequest> TYPE = payloadType("look_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, LookRequest> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LookRequest::pos, Direction.STREAM_CODEC, LookRequest::face, LookRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ToggleSide(BlockPos pos, Direction side) implements CustomPacketPayload {
        public static final Type<ToggleSide> TYPE = payloadType("toggle_side");
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleSide> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ToggleSide::pos, Direction.STREAM_CODEC, ToggleSide::side, ToggleSide::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RenameConnector(BlockPos pos, String name) implements CustomPacketPayload {
        public static final Type<RenameConnector> TYPE = payloadType("rename_connector");
        public static final StreamCodec<RegistryFriendlyByteBuf, RenameConnector> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RenameConnector::pos, ByteBufCodecs.stringUtf8(Screens.MAX_NAME_LENGTH), RenameConnector::name,
                RenameConnector::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Renames the device on one side of a connector, or one of its faces when {@code face} is present. */
    public record RenameDevice(BlockPos pos, Direction side, java.util.Optional<FaceKey> face, String name) implements CustomPacketPayload {
        public static final Type<RenameDevice> TYPE = payloadType("rename_device");
        public static final StreamCodec<RegistryFriendlyByteBuf, RenameDevice> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RenameDevice::pos,
                Direction.STREAM_CODEC, RenameDevice::side,
                ByteBufCodecs.optional(FaceKey.STREAM_CODEC), RenameDevice::face,
                ByteBufCodecs.stringUtf8(Screens.MAX_NAME_LENGTH), RenameDevice::name,
                RenameDevice::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Controller settings. An empty password leaves the stored one unchanged. */
    public record ControllerSettings(BlockPos pos, String name, AccessMode mode, String password) implements CustomPacketPayload {
        public static final Type<ControllerSettings> TYPE = payloadType("controller_settings");
        public static final StreamCodec<RegistryFriendlyByteBuf, ControllerSettings> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ControllerSettings::pos,
                ByteBufCodecs.stringUtf8(Screens.MAX_NAME_LENGTH), ControllerSettings::name,
                AccessMode.STREAM_CODEC, ControllerSettings::mode,
                ByteBufCodecs.stringUtf8(Screens.MAX_NAME_LENGTH), ControllerSettings::password,
                ControllerSettings::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- controller switches

    /**
     * The controller screen's Network tab: switch the network on or off ({@code what} NETWORK, {@code value} 0/1), set
     * its redstone mode (REDSTONE, {@code value} the mode's ordinal), or switch a sheet on or off (SHEET).
     */
    public record ControllerSwitch(BlockPos pos, int what, java.util.Optional<java.util.UUID> sheet, int value) implements CustomPacketPayload {
        public static final int NETWORK = 0;
        public static final int REDSTONE = 1;
        public static final int SHEET = 2;
        public static final Type<ControllerSwitch> TYPE = payloadType("controller_switch");
        public static final StreamCodec<RegistryFriendlyByteBuf, ControllerSwitch> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ControllerSwitch::pos,
                ByteBufCodecs.VAR_INT, ControllerSwitch::what,
                ByteBufCodecs.optional(net.minecraft.core.UUIDUtil.STREAM_CODEC), ControllerSwitch::sheet,
                ByteBufCodecs.VAR_INT, ControllerSwitch::value,
                ControllerSwitch::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- identify

    /** Something to light up in the world: a block (or one face of it), its color and the name to show above it. */
    public record IdentifyMarker(net.minecraft.core.GlobalPos pos, java.util.Optional<Direction> face, int color, String label) {
        public static final StreamCodec<RegistryFriendlyByteBuf, IdentifyMarker> STREAM_CODEC = StreamCodec.composite(
                net.minecraft.core.GlobalPos.STREAM_CODEC, IdentifyMarker::pos,
                ByteBufCodecs.optional(Direction.STREAM_CODEC), IdentifyMarker::face,
                ByteBufCodecs.INT, IdentifyMarker::color,
                ByteBufCodecs.stringUtf8(256), IdentifyMarker::label,
                IdentifyMarker::new);
    }

    /** Everything the player should see lit up; replaces what the client had. */
    public record IdentifyMarkers(java.util.List<IdentifyMarker> markers) implements CustomPacketPayload {
        public static final Type<IdentifyMarkers> TYPE = payloadType("identify_markers");
        public static final StreamCodec<RegistryFriendlyByteBuf, IdentifyMarkers> STREAM_CODEC =
                IdentifyMarker.STREAM_CODEC.apply(ByteBufCodecs.list(4096)).map(IdentifyMarkers::new, IdentifyMarkers::markers);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * From the connector screens: identify the connector itself (no side), the device on a side, or one face of that
     * device.
     */
    public record ToggleIdentify(BlockPos pos, java.util.Optional<Direction> side, java.util.Optional<FaceKey> face) implements CustomPacketPayload {
        public static final Type<ToggleIdentify> TYPE = payloadType("toggle_identify");
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleIdentify> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ToggleIdentify::pos,
                ByteBufCodecs.optional(Direction.STREAM_CODEC), ToggleIdentify::side,
                ByteBufCodecs.optional(FaceKey.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()), ToggleIdentify::face,
                ToggleIdentify::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- linking wireless connectors

    /** A network offered by the picker; {@code password} when the player must enter its password to join. */
    public record NetworkChoice(java.util.UUID id, String name, String owner, boolean password) {
        public static final StreamCodec<RegistryFriendlyByteBuf, NetworkChoice> STREAM_CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, NetworkChoice::id,
                ByteBufCodecs.STRING_UTF8, NetworkChoice::name,
                ByteBufCodecs.STRING_UTF8, NetworkChoice::owner,
                ByteBufCodecs.BOOL, NetworkChoice::password,
                NetworkChoice::new);
    }

    /** Opens the network picker, for the network tool (no connector) or for one connector. */
    public record OpenNetworkPicker(java.util.Optional<BlockPos> connector, java.util.List<NetworkChoice> choices) implements CustomPacketPayload {
        public static final Type<OpenNetworkPicker> TYPE = payloadType("open_network_picker");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenNetworkPicker> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), OpenNetworkPicker::connector,
                NetworkChoice.STREAM_CODEC.apply(ByteBufCodecs.list(4096)), OpenNetworkPicker::choices,
                OpenNetworkPicker::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestNetworkPicker(java.util.Optional<BlockPos> connector) implements CustomPacketPayload {
        public static final Type<RequestNetworkPicker> TYPE = payloadType("request_network_picker");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestNetworkPicker> STREAM_CODEC =
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC).<RegistryFriendlyByteBuf>cast().map(RequestNetworkPicker::new, RequestNetworkPicker::connector);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The picker's choice; {@code password} is only used to join a password network. */
    /** "Unlink tool" in the network picker: the held network tool forgets its network. */
    public record UnlinkTool() implements CustomPacketPayload {
        public static final Type<UnlinkTool> TYPE = payloadType("unlink_tool");
        public static final StreamCodec<RegistryFriendlyByteBuf, UnlinkTool> STREAM_CODEC = StreamCodec.unit(new UnlinkTool());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PickNetwork(java.util.Optional<BlockPos> connector, java.util.UUID network, String password) implements CustomPacketPayload {
        public static final Type<PickNetwork> TYPE = payloadType("pick_network");
        public static final StreamCodec<RegistryFriendlyByteBuf, PickNetwork> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), PickNetwork::connector,
                net.minecraft.core.UUIDUtil.STREAM_CODEC, PickNetwork::network,
                ByteBufCodecs.stringUtf8(Screens.MAX_NAME_LENGTH), PickNetwork::password,
                PickNetwork::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sneak + scroll with the network tool: the next (1) or previous (-1) mode. */
    public record CycleToolMode(int step) implements CustomPacketPayload {
        public static final Type<CycleToolMode> TYPE = payloadType("cycle_tool_mode");
        public static final StreamCodec<RegistryFriendlyByteBuf, CycleToolMode> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.<RegistryFriendlyByteBuf>cast().map(CycleToolMode::new, CycleToolMode::step);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The facade screen's pick for the facades in that hand: a block, or none to make them blank. */
    public record PaintFacade(net.minecraft.world.InteractionHand hand, java.util.Optional<net.minecraft.world.level.block.Block> block)
            implements CustomPacketPayload {
        public static final Type<PaintFacade> TYPE = payloadType("paint_facade");
        public static final StreamCodec<RegistryFriendlyByteBuf, PaintFacade> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL.map(main -> main ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND,
                        hand -> hand == net.minecraft.world.InteractionHand.MAIN_HAND), PaintFacade::hand,
                ByteBufCodecs.optional(ByteBufCodecs.registry(net.minecraft.core.registries.Registries.BLOCK)), PaintFacade::block,
                PaintFacade::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- registration

    /**
     * Opens (or refreshes) a Status Beacon's screen: a line on what it shows (its network and reach, or the signal), its
     * settings (mode and looks), the signal it gets now, what is going off, and whether the player may change it.
     */
    /** {@code refresh}: new contents after a change made in the screen, ignored by the client once it is closed. */
    public record OpenBeaconScreen(BlockPos pos, net.minecraft.network.chat.Component status, net.minecraft.nbt.CompoundTag settings, int signal,
            java.util.List<String> alarms, boolean canEdit, boolean refresh) implements CustomPacketPayload {
        public static final Type<OpenBeaconScreen> TYPE = payloadType("open_beacon_screen");
        private static final StreamCodec<io.netty.buffer.ByteBuf, java.util.List<String>> ALARMS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBeaconScreen> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    BlockPos.STREAM_CODEC.encode(buf, p.pos());
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, p.status());
                    ByteBufCodecs.COMPOUND_TAG.encode(buf, p.settings());
                    ByteBufCodecs.VAR_INT.encode(buf, p.signal());
                    ALARMS.encode(buf, p.alarms());
                    buf.writeBoolean(p.canEdit());
                    buf.writeBoolean(p.refresh());
                },
                buf -> new OpenBeaconScreen(BlockPos.STREAM_CODEC.decode(buf), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.COMPOUND_TAG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ALARMS.decode(buf), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A Status Beacon's settings from its screen (mode and looks); the server keeps them within its limits. */
    public record BeaconSettings(BlockPos pos, net.minecraft.nbt.CompoundTag settings) implements CustomPacketPayload {
        public static final Type<BeaconSettings> TYPE = payloadType("beacon_settings");
        public static final StreamCodec<RegistryFriendlyByteBuf, BeaconSettings> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, BeaconSettings::pos,
                ByteBufCodecs.COMPOUND_TAG, BeaconSettings::settings,
                BeaconSettings::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What is going off on the player's networks: by network, bit 0 critical (else a warning), bit 1 not theirs (only viewable). */
    public record AlarmStates(java.util.Map<java.util.UUID, Integer> states) implements CustomPacketPayload {
        public static final Type<AlarmStates> TYPE = payloadType("alarm_states");
        public static final StreamCodec<RegistryFriendlyByteBuf, AlarmStates> STREAM_CODEC = ByteBufCodecs
                .<RegistryFriendlyByteBuf, java.util.UUID, Integer, java.util.Map<java.util.UUID, Integer>>map(java.util.HashMap::new,
                        net.minecraft.core.UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.VAR_INT.cast(), 4096)
                .map(AlarmStates::new, AlarmStates::states);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A block with something wrong, for the network tool's Status mode: 0 a warning, 1 critical. */
    /** A block with something wrong; its label is text the client puts in its own language. */
    public record StatusMarker(BlockPos pos, int severity, net.minecraft.network.chat.Component label) {
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusMarker> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StatusMarker::pos,
                ByteBufCodecs.VAR_INT, StatusMarker::severity,
                net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC, StatusMarker::label,
                StatusMarker::new);
    }

    public record StatusMarkers(java.util.List<StatusMarker> markers) implements CustomPacketPayload {
        public static final Type<StatusMarkers> TYPE = payloadType("status_markers");
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusMarkers> STREAM_CODEC =
                StatusMarker.STREAM_CODEC.apply(ByteBufCodecs.list(256)).map(StatusMarkers::new, StatusMarkers::markers);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Asked now and then while the network tool is held in Status mode. */
    public record RequestStatusMarkers() implements CustomPacketPayload {
        public static final Type<RequestStatusMarkers> TYPE = payloadType("request_status_markers");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestStatusMarkers> STREAM_CODEC = StreamCodec.unit(new RequestStatusMarkers());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // Lambdas (not method references) keep client classes from loading on a dedicated server.
        registrar.playToClient(OpenBlockScreen.TYPE, OpenBlockScreen.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openBlockScreen(payload.view(), payload.refresh()));
        registrar.playToClient(OpenControllerScreen.TYPE, OpenControllerScreen.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openControllerScreen(payload.view(), payload.refresh()));
        registrar.playToServer(ControllerSwitch.TYPE, ControllerSwitch.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.controllerSwitch(player, payload)));
        registrar.playToClient(IdentifyMarkers.TYPE, IdentifyMarkers.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.IdentifyRenderer.accept(payload.markers()));
        registrar.playToServer(ToggleIdentify.TYPE, ToggleIdentify.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.toggleIdentify(player, payload)));
        registrar.playToClient(AlarmStates.TYPE, AlarmStates.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.AlarmClient.acceptStates(payload.states()));
        registrar.playToClient(StatusMarkers.TYPE, StatusMarkers.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.AlarmClient.acceptMarkers(payload.markers()));
        registrar.playToServer(RequestStatusMarkers.TYPE, RequestStatusMarkers.STREAM_CODEC,
                (payload, context) -> server(context, AlarmPush::sendMarkers));
        registrar.playToClient(OpenBeaconScreen.TYPE, OpenBeaconScreen.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openBeaconScreen(payload));
        registrar.playToServer(BeaconSettings.TYPE, BeaconSettings.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.setBeaconSettings(player, payload)));
        registrar.playToClient(OpenNetworkPicker.TYPE, OpenNetworkPicker.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openNetworkPicker(payload));
        registrar.playToServer(RequestNetworkPicker.TYPE, RequestNetworkPicker.STREAM_CODEC,
                (payload, context) -> server(context, player -> Linking.openPicker(player, payload.connector())));
        registrar.playToServer(UnlinkTool.TYPE, UnlinkTool.STREAM_CODEC,
                (payload, context) -> server(context, Linking::unlinkTool));
        registrar.playToServer(PickNetwork.TYPE, PickNetwork.STREAM_CODEC,
                (payload, context) -> server(context, player -> Linking.pick(player, payload)));
        registrar.playToServer(PaintFacade.TYPE, PaintFacade.STREAM_CODEC,
                (payload, context) -> server(context, player -> io.github.leadassimilator.reticulum.item.FacadeItem.paint(player, payload)));
        registrar.playToServer(CycleToolMode.TYPE, CycleToolMode.STREAM_CODEC,
                (payload, context) -> server(context, player -> Linking.cycleMode(player, payload.step())));
        registrar.playToClient(LookResponse.TYPE, LookResponse.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.LookHud.accept(payload.pos(), payload.lines()));

        registrar.playToServer(LookRequest.TYPE, LookRequest.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.lookAt(player, payload)));
        registrar.playToServer(ToggleSide.TYPE, ToggleSide.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.toggleSide(player, payload)));
        registrar.playToServer(RenameConnector.TYPE, RenameConnector.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.renameConnector(player, payload)));
        registrar.playToServer(RenameDevice.TYPE, RenameDevice.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.renameDevice(player, payload)));
        registrar.playToServer(ControllerSettings.TYPE, ControllerSettings.STREAM_CODEC,
                (payload, context) -> server(context, player -> Screens.controllerSettings(player, payload)));
    }

    private static void server(IPayloadContext context, java.util.function.Consumer<ServerPlayer> action) {
        if (context.player() instanceof ServerPlayer player) {
            action.accept(player);
        }
    }
}
