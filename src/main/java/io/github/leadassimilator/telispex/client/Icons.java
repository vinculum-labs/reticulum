package io.github.leadassimilator.telispex.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Icons for pages, as PNGs at {@code _/icon/<kind>/<namespace>/<path>.png} beside each page: items exactly as the game
 * draws them in inventories, fluids as their still texture, and any kind a mod adds ({@link #register}). Drawn on the
 * render thread into an offscreen texture, and kept until resources reload.
 */
public final class Icons {
    static final int SIZE = 64;
    private static final Map<String, Function<Identifier, CompletableFuture<byte[]>>> KINDS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<byte[]>> CACHE = new ConcurrentHashMap<>();

    /** The offscreen textures and their projection, made once and reused for every icon (a picker loads dozens). */
    @Nullable
    private static CachedOrthoProjectionMatrixBuffer projection;
    @Nullable
    private static GpuTexture color;
    @Nullable
    private static GpuTextureView colorView;
    @Nullable
    private static GpuTexture depth;
    @Nullable
    private static GpuTextureView depthView;

    static {
        KINDS.put("item", Icons::item);
        KINDS.put("fluid", Icons::fluid);
    }

    private Icons() {
    }

    /**
     * Adds a kind of icon. {@code draw} runs on the render thread and gives a PNG, now or once the GPU has drawn it:
     * {@link #sprite} and {@link #draw} help, and {@link #transparent} stands for an id it doesn't know.
     */
    public static void register(String kind, Function<Identifier, CompletableFuture<byte[]>> draw) {
        if (!kind.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Not a valid icon kind: " + kind);
        }
        if (KINDS.putIfAbsent(kind, draw) != null) {
            throw new IllegalStateException("Icons of kind " + kind + " are already drawn");
        }
    }

    /** An icon, drawn once and kept; null for a kind nobody draws. */
    @Nullable
    static CompletableFuture<byte[]> get(String kind, Identifier id) {
        Function<Identifier, CompletableFuture<byte[]>> draw = KINDS.get(kind);
        return draw == null ? null
                : CACHE.computeIfAbsent(kind + "/" + id, key -> Minecraft.getInstance().submit(() -> draw.apply(id)).thenCompose(png -> png));
    }

    /** Resources reloaded (F3+T, a resource pack): icons are drawn again as they are asked for. */
    static void clear() {
        CACHE.clear();
    }

    /** An item as inventories show it. */
    private static CompletableFuture<byte[]> item(Identifier id) {
        ItemStack stack = BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
        if (stack.isEmpty() || stack.is(Items.AIR)) {
            return CompletableFuture.completedFuture(transparent());
        }
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        return draw(id, (pose, buffers, collector) -> {
            // As the game draws items in its GUI: centered, flipped upright, lit flat or as a block.
            pose.translate(SIZE / 2f, SIZE / 2f, 0);
            pose.scale(SIZE, -SIZE, SIZE);
            mc.gameRenderer.getLighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
            state.submit(pose, collector, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        });
    }

    /** A fluid as its still texture, tinted, filling the square. */
    private static CompletableFuture<byte[]> fluid(Identifier id) {
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return CompletableFuture.completedFuture(transparent());
        }
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
        return sprite(id, extensions.getStillTexture(), extensions.getTintColor());
    }

    /** A block-atlas texture, tinted (ARGB; alpha 0 counts as opaque), filling the square. On the render thread. */
    public static CompletableFuture<byte[]> sprite(Identifier id, @Nullable Identifier texture, int tint) {
        if (texture == null) {
            return CompletableFuture.completedFuture(transparent());
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(texture);
        int color = (tint >>> 24) == 0 ? 0xFF000000 | tint : tint;
        return draw(id, (pose, buffers, collector) -> {
            VertexConsumer quad = buffers.getBuffer(RenderTypes.text(TextureAtlas.LOCATION_BLOCKS));
            Matrix4f matrix = pose.last().pose();
            vertex(quad, matrix, 0, 0, sprite.getU0(), sprite.getV0(), color);
            vertex(quad, matrix, 0, SIZE, sprite.getU0(), sprite.getV1(), color);
            vertex(quad, matrix, SIZE, SIZE, sprite.getU1(), sprite.getV1(), color);
            vertex(quad, matrix, SIZE, 0, sprite.getU1(), sprite.getV0(), color);
        });
    }

    private static void vertex(VertexConsumer quad, Matrix4f matrix, float x, float y, float u, float v, int color) {
        quad.addVertex(matrix, x, y, 0).setColor(color).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
    }

    /** Draws an icon: into {@code buffers}, or submitted to {@code collector} (as items are). */
    @FunctionalInterface
    public interface Drawing {
        void draw(PoseStack pose, MultiBufferSource buffers, SubmitNodeCollector collector);
    }

    /**
     * Whatever {@code draw} puts in a {@code SIZE}-pixel square (x right, y down, from the top-left corner), as a PNG
     * once the GPU has drawn it. On the render thread; {@code id} only names it in the log should it fail.
     */
    public static CompletableFuture<byte[]> draw(Identifier id, Drawing draw) {
        Minecraft mc = Minecraft.getInstance();
        GpuDevice device = RenderSystem.getDevice();
        if (color == null) {
            projection = new CachedOrthoProjectionMatrixBuffer("Telispex icons", -1000f, 1000f, true);
            color = device.createTexture(() -> "Telispex icon", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    TextureFormat.RGBA8, SIZE, SIZE, 1, 1);
            colorView = device.createTextureView(color);
            depth = device.createTexture(() -> "Telispex icon depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    mc.getMainRenderTarget().getDepthTexture().getFormat(), SIZE, SIZE, 1, 1);
            depthView = device.createTextureView(depth);
        }
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(color, 0, depth, 1.0);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection.getBuffer(SIZE, SIZE), ProjectionType.ORTHOGRAPHIC);
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            draw.draw(new PoseStack(), buffers, mc.gameRenderer.getSubmitNodeStorage());
            mc.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
            buffers.endBatch();
        } catch (RuntimeException e) {
            Telispex.LOGGER.warn("Could not render icon for {}", id, e);
            return CompletableFuture.completedFuture(transparent());
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            RenderSystem.restoreProjectionMatrix();
        }
        return read(id, color, encoder);
    }

    /**
     * The texture's pixels, as a PNG, once the GPU has copied them out. Mapping the buffer doesn't wait for the copy
     * (the game maps buffers persistently), so they're read when the copy's fence passes, a frame or so later. Icons
     * drawn meanwhile reuse the texture: the GPU copies this one out first.
     */
    private static CompletableFuture<byte[]> read(Identifier id, GpuTexture texture, CommandEncoder encoder) {
        int pixelSize = texture.getFormat().pixelSize();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Telispex icon", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                SIZE * SIZE * pixelSize);
        try {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {
                try (buffer; GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false); NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            // Rows come bottom up.
                            image.setPixelABGR(x, SIZE - 1 - y, view.data().getInt((x + y * SIZE) * pixelSize));
                        }
                    }
                    result.complete(png(image));
                } catch (IOException | RuntimeException e) {
                    Telispex.LOGGER.warn("Could not read the icon for {}", id, e);
                    result.complete(transparent());
                }
            }, 0);
        } catch (RuntimeException e) {
            buffer.close();
            Telispex.LOGGER.warn("Could not read the icon for {}", id, e);
            result.complete(transparent());
        }
        return result;
    }

    /** A blank PNG, for an id with nothing to show. */
    public static byte[] transparent() {
        try (NativeImage image = new NativeImage(1, 1, true)) {
            image.setPixel(0, 0, 0);
            return png(image);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** An image as PNG bytes, written by stb as NativeImage#writeToFile does, but into memory. */
    private static byte[] png(NativeImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        // ABGR ints are R, G, B, A bytes in little-endian order: what stb takes for 4 channels.
        int[] pixels = image.getPixelsABGR();
        ByteBuffer rgba = MemoryUtil.memAlloc(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (STBIWriteCallback write = STBIWriteCallback.create((context, data, size) -> {
            byte[] chunk = new byte[size];
            STBIWriteCallback.getData(data, size).get(chunk);
            out.write(chunk, 0, size);
        })) {
            rgba.asIntBuffer().put(pixels);
            if (!STBImageWrite.stbi_write_png_to_func(write, 0L, width, height, 4, rgba, width * 4)) {
                throw new IOException("stb couldn't write the PNG");
            }
        } finally {
            MemoryUtil.memFree(rgba);
        }
        return out.toByteArray();
    }
}
