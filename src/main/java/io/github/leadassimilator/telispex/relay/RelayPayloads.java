package io.github.leadassimilator.telispex.relay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.github.leadassimilator.telispex.Telispex;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Carries an app's page messages (JSON) between the game client and the server, each marked with the app it belongs to.
 * Server messages can be large, so they are compressed and split into chunks; so are large requests, which would not
 * fit one client-to-server packet.
 */
public final class RelayPayloads {
    /** App ids are short: a host name label and a resource namespace. */
    private static final int APP_CHARS = 63;

    /** Client-to-server packets are limited to 32 KiB; stay below it. */
    public static final int MAX_REQUEST_CHARS = 30_000;
    private static final int CHUNK_BYTES = 512 * 1024;

    private RelayPayloads() {
    }

    public record Request(String app, String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Telispex.ID, "request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(APP_CHARS), Request::app,
                ByteBufCodecs.stringUtf8(MAX_REQUEST_CHARS), Request::json,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Compressed bytes in one piece of a large request: well within a client-to-server packet. */
    public static final int PART_BYTES = 24 * 1024;
    /** The most pieces one request may come in, and the largest it may be, decompressed. */
    public static final int MAX_PARTS = 256;
    public static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;

    /**
     * One piece of a large compressed request. The server joins pieces with the same {@code message} id; {@code id} is the
     * request's own id ("" for none), for answering one refused before it is joined.
     */
    public record RequestPart(String app, int message, String id, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<RequestPart> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Telispex.ID, "request_part"));
        public static final StreamCodec<ByteBuf, RequestPart> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(APP_CHARS), RequestPart::app,
                ByteBufCodecs.VAR_INT, RequestPart::message,
                ByteBufCodecs.stringUtf8(256), RequestPart::id,
                ByteBufCodecs.VAR_INT, RequestPart::index,
                ByteBufCodecs.VAR_INT, RequestPart::total,
                ByteBufCodecs.byteArray(PART_BYTES), RequestPart::data,
                RequestPart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A large request, compressed and split into pieces for the server. */
    public static List<RequestPart> splitRequest(String app, int message, String id, String json) {
        String requestId = id == null ? "" : id.length() > 256 ? id.substring(0, 256) : id;
        byte[] compressed = deflate(json.getBytes(StandardCharsets.UTF_8));
        int total = Math.max(1, (compressed.length + PART_BYTES - 1) / PART_BYTES);
        List<RequestPart> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * PART_BYTES;
            int to = Math.min(compressed.length, from + PART_BYTES);
            byte[] part = new byte[to - from];
            System.arraycopy(compressed, from, part, 0, part.length);
            parts.add(new RequestPart(app, message, requestId, i, total, part));
        }
        return parts;
    }

    /** One piece of a compressed server message. The client joins pieces with the same {@code message} id. */
    public record Chunk(String app, int message, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Telispex.ID, "chunk"));
        public static final StreamCodec<ByteBuf, Chunk> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(APP_CHARS), Chunk::app,
                ByteBufCodecs.VAR_INT, Chunk::message,
                ByteBufCodecs.VAR_INT, Chunk::index,
                ByteBufCodecs.VAR_INT, Chunk::total,
                ByteBufCodecs.byteArray(CHUNK_BYTES + 64), Chunk::data,
                Chunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static List<Chunk> split(String app, int message, String json) {
        byte[] compressed = deflate(json.getBytes(StandardCharsets.UTF_8));
        int total = Math.max(1, (compressed.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        List<Chunk> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_BYTES;
            int to = Math.min(compressed.length, from + CHUNK_BYTES);
            byte[] part = new byte[to - from];
            System.arraycopy(compressed, from, part, 0, part.length);
            chunks.add(new Chunk(app, message, i, total, part));
        }
        return chunks;
    }

    /** The largest message a client accepts, decompressed. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    /** The most pieces one message may come in. */
    public static final int MAX_CHUNKS = 4096;

    public static String join(List<byte[]> parts) throws DataFormatException {
        return join(parts, MAX_MESSAGE_BYTES);
    }

    /** As {@link #join(List)}, refusing more than {@code limit} bytes decompressed. */
    public static String join(List<byte[]> parts, int limit) throws DataFormatException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            all.writeBytes(part);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(all.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        while (!inflater.finished()) {
            int n = inflater.inflate(buffer);
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                break;
            }
            out.write(buffer, 0, n);
            if (out.size() > limit) {
                inflater.end();
                throw new DataFormatException("message larger than " + limit + " bytes");
            }
        }
        inflater.end();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        byte[] buffer = new byte[64 * 1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
