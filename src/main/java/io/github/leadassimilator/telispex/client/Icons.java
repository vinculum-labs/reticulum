package io.github.leadassimilator.telispex.client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

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
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return transparent();
        }
        var extensions = IClientFluidTypeExtensions.of(fluid);
        return sprite(id, extensions.getStillTexture(), extensions.getTintColor());
    }

    /** A block-atlas texture, tinted (ARGB; alpha 0 counts as opaque), as a 16x16 square. On the render thread. */
    public static byte[] sprite(ResourceLocation id, @Nullable ResourceLocation texture, int tint) {
        if (texture == null) {
            return transparent();
        }
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        float a = (tint >>> 24) == 0 ? 1f : ((tint >>> 24) & 0xFF) / 255f;
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        return draw(id, graphics -> {
            RenderSystem.enableBlend();
            graphics.blit(0, 0, 0, 16, 16, sprite, r, g, b, a);
            RenderSystem.disableBlend();
        });
    }

    /** One offscreen target, reused for every icon (making one per icon is slow when a picker loads dozens). */
    @Nullable
    private static TextureTarget target;

    private static TextureTarget target() {
        if (target == null) {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        }
        return target;
    }

    /**
     * Whatever {@code draw} puts in a 16x16 GUI square, as a PNG. On the render thread; {@code id} only names it in the
     * log should it fail.
     */
    public static byte[] draw(ResourceLocation id, Consumer<GuiGraphics> draw) {
        Minecraft mc = Minecraft.getInstance();
        TextureTarget target = target();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            float far = ClientHooks.getGuiFarPlane();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, 1000, far), VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0, 0, 10000F - far);
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw.accept(graphics);
            graphics.flush();

            try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                image.downloadTexture(0, false);
                image.flipY();
                return image.asByteArray();
            }
        } catch (IOException | RuntimeException e) {
            Telispex.LOGGER.warn("Could not render icon for {}", id, e);
            return transparent();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** A blank PNG, for an id with nothing to show. */
    public static byte[] transparent() {
        try (NativeImage image = new NativeImage(1, 1, true)) {
            image.setPixelRGBA(0, 0, 0);
            return image.asByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
