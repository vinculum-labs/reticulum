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
import org.joml.Matrix4fStack;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * Icons for pages, as PNGs at {@code _/icon/<kind>/<namespace>/<path>.png} beside each page: items exactly as the game
 * draws them in inventories, fluids as their still texture, and any kind a mod adds ({@link #register}). Drawn on the
 * render thread into an offscreen target, and kept until resources reload.
 */
public final class Icons {
    static final int SIZE = 64;
    private static final Map<String, Function<ResourceLocation, byte[]>> KINDS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<byte[]>> CACHE = new ConcurrentHashMap<>();

    static {
        KINDS.put("item", Icons::item);
        KINDS.put("fluid", Icons::fluid);
    }

    private Icons() {
    }

    /**
     * Adds a kind of icon. {@code draw} runs on the render thread and gives a PNG: {@link #sprite} and {@link #draw}
     * help, and {@link #transparent} stands for an id it doesn't know.
     */
    public static void register(String kind, Function<ResourceLocation, byte[]> draw) {
        if (!kind.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Not a valid icon kind: " + kind);
        }
        if (KINDS.putIfAbsent(kind, draw) != null) {
            throw new IllegalStateException("Icons of kind " + kind + " are already drawn");
        }
    }

    /** An icon, drawn once and kept; null for a kind nobody draws. */
    @Nullable
    static CompletableFuture<byte[]> get(String kind, ResourceLocation id) {
        Function<ResourceLocation, byte[]> draw = KINDS.get(kind);
        return draw == null ? null : CACHE.computeIfAbsent(kind + "/" + id, key -> Minecraft.getInstance().submit(() -> draw.apply(id)));
    }

    /** Resources reloaded (F3+T, a resource pack): icons are drawn again as they are asked for. */
    static void clear() {
        CACHE.clear();
    }

    private static byte[] item(ResourceLocation id) {
        ItemStack stack = BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
        return stack.isEmpty() || stack.is(Items.AIR) ? transparent() : draw(id, graphics -> graphics.renderItem(stack, 0, 0));
    }

    /** A fluid as its still texture, tinted, filling the square. */
    private static byte[] fluid(ResourceLocation id) {
        net.minecraft.world.level.material.Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return transparent();
        }
        var extensions = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
        return sprite(id, extensions.getStillTexture(), extensions.getTintColor());
    }

    /** A block-atlas texture, tinted (ARGB; alpha 0 counts as opaque), as a 16x16 square. On the render thread. */
    public static byte[] sprite(ResourceLocation id, @Nullable ResourceLocation texture, int tint) {
        if (texture == null) {
            return transparent();
        }
        var sprite = Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).apply(texture);
        int color = (tint >>> 24) == 0 ? 0xFF000000 | tint : tint;
        return draw(id, graphics -> graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, sprite, 0, 0, 16, 16, color));
    }

    /** One offscreen target, reused for every icon (making one per icon is slow when a picker loads dozens). */
    @Nullable
    private static TextureTarget target;

    private static TextureTarget target() {
        if (target == null) {
            target = new TextureTarget("Telispex icons", SIZE, SIZE, true);
        }
        return target;
    }

    /**
     * Whatever {@code draw} puts in a 16x16 GUI square, as a PNG. On the render thread, between frames; {@code id} only
     * names it in the log should it fail. GUI and item rendering always draw to the main render target, so the icon
     * target stands in for it meanwhile.
     */
    public static byte[] draw(ResourceLocation id, java.util.function.Consumer<GuiGraphics> draw) {
        Minecraft mc = Minecraft.getInstance();
        TextureTarget target = target();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderTarget main = mc.mainRenderTarget;
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
            mc.mainRenderTarget = target;
            float far = ClientHooks.getGuiFarPlane();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, 1000, far), ProjectionType.ORTHOGRAPHIC);
            modelView.translation(0, 0, 10000F - far);
            Lighting.setupFor3DItems();
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw.accept(graphics);
            graphics.flush();
        } catch (RuntimeException e) {
            Telispex.LOGGER.warn("Could not render icon for {}", id, e);
            return transparent();
        } finally {
            mc.mainRenderTarget = main;
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
        return read(id, target.getColorTexture(), encoder);
    }

    /** The texture's pixels, as a PNG. Reading the buffer waits for the copy, so this returns them right away. */
    private static byte[] read(ResourceLocation id, GpuTexture texture, CommandEncoder encoder) {
        try (GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Telispex icon", BufferType.PIXEL_PACK, BufferUsage.STATIC_READ,
                SIZE * SIZE * texture.getFormat().pixelSize())) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {
            }, 0);
            try (GpuBuffer.ReadView view = encoder.readBuffer(buffer); NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        // Rows come bottom up.
                        image.setPixelABGR(x, SIZE - 1 - y, view.data().getInt((x + y * SIZE) * texture.getFormat().pixelSize()));
                    }
                }
                return png(image);
            }
        } catch (IOException | RuntimeException e) {
            Telispex.LOGGER.warn("Could not read the icon for {}", id, e);
            return transparent();
        }
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
