package io.github.leadassimilator.reticulum.protocol.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.github.leadassimilator.reticulum.Reticulum;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Carries web UI protocol messages (JSON) between the game client and the server. Requests from the browser are
 * small; server messages (snapshots) can be large, so they are compressed and split into chunks.
 */
public final class WebPayloads {
    /** Client-to-server packets are limited to 32 KiB; stay below it. */
    public static final int MAX_REQUEST_CHARS = 30_000;
    private static final int CHUNK_BYTES = 512 * 1024;

    private WebPayloads() {
    }

    public record Request(String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "web_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC =
                ByteBufCodecs.stringUtf8(MAX_REQUEST_CHARS).map(Request::new, Request::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One piece of a compressed server message. The client joins pieces with the same {@code message} id. */
    public record Chunk(int message, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "web_chunk"));
        public static final StreamCodec<ByteBuf, Chunk> STREAM_CODEC = StreamCodec.composite(
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

    public static List<Chunk> split(int message, String json) {
        byte[] compressed = deflate(json.getBytes(StandardCharsets.UTF_8));
        int total = Math.max(1, (compressed.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        List<Chunk> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_BYTES;
            int to = Math.min(compressed.length, from + CHUNK_BYTES);
            byte[] part = new byte[to - from];
            System.arraycopy(compressed, from, part, 0, part.length);
            chunks.add(new Chunk(message, i, total, part));
        }
        return chunks;
    }

    /** The largest message a client accepts, decompressed. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    /** The most pieces one message may come in. */
    public static final int MAX_CHUNKS = 4096;

    public static String join(List<byte[]> parts) throws DataFormatException {
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
            if (out.size() > MAX_MESSAGE_BYTES) {
                inflater.end();
                throw new DataFormatException("message larger than " + MAX_MESSAGE_BYTES + " bytes");
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
