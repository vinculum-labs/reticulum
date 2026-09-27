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

    public record OpenBlockScreen(Views.BlockView view) implements CustomPacketPayload {
        public static final Type<OpenBlockScreen> TYPE = payloadType("open_block_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBlockScreen> STREAM_CODEC =
                Views.BlockView.STREAM_CODEC.map(OpenBlockScreen::new, OpenBlockScreen::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenControllerScreen(Views.ControllerView view) implements CustomPacketPayload {
        public static final Type<OpenControllerScreen> TYPE = payloadType("open_controller_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenControllerScreen> STREAM_CODEC =
                Views.ControllerView.STREAM_CODEC.map(OpenControllerScreen::new, OpenControllerScreen::view);

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

    // ---------------------------------------------------------------- registration

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // Lambdas (not method references) keep client classes from loading on a dedicated server.
        registrar.playToClient(OpenBlockScreen.TYPE, OpenBlockScreen.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openBlockScreen(payload.view()));
        registrar.playToClient(OpenControllerScreen.TYPE, OpenControllerScreen.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.ClientPayloadHandler.openControllerScreen(payload.view()));
        registrar.playToClient(LookResponse.TYPE, LookResponse.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.LookHud.accept(payload.pos(), payload.lines()));

        registrar.playToClient(io.github.leadassimilator.reticulum.protocol.web.WebPayloads.Chunk.TYPE,
                io.github.leadassimilator.reticulum.protocol.web.WebPayloads.Chunk.STREAM_CODEC,
                (payload, context) -> io.github.leadassimilator.reticulum.client.web.WebBridge.onChunk(payload));
        registrar.playToServer(io.github.leadassimilator.reticulum.protocol.web.WebPayloads.Request.TYPE,
                io.github.leadassimilator.reticulum.protocol.web.WebPayloads.Request.STREAM_CODEC,
                (payload, context) -> server(context, player -> io.github.leadassimilator.reticulum.protocol.web.WebApi.onRequest(player, payload.json())));

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
