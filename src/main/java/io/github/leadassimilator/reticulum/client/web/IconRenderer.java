package io.github.leadassimilator.reticulum.client.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

import io.github.leadassimilator.reticulum.Reticulum;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * Renders item icons to PNG for the web UI, exactly as the game draws them in inventories. Rendering happens on the
 * render thread into an offscreen target; results are cached for the session.
 */
final class IconRenderer {
    static final int SIZE = 64;
    private static final Map<ResourceLocation, CompletableFuture<byte[]>> CACHE = new ConcurrentHashMap<>();

    private IconRenderer() {
    }

    private static final Map<String, CompletableFuture<byte[]>> OTHER = new ConcurrentHashMap<>();

    /** Resources reloaded (F3+T, a resource pack): icons are drawn again as they are asked for. */
    static void clear() {
        CACHE.clear();
        OTHER.clear();
    }

    static CompletableFuture<byte[]> item(ResourceLocation id) {
        return CACHE.computeIfAbsent(id, key -> Minecraft.getInstance().submit(() -> {
            ItemStack stack = BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
            return stack.isEmpty() || stack.is(Items.AIR) ? transparent() : render(key, graphics -> graphics.renderItem(stack, 0, 0));
        }));
    }

    /** A fluid as its still texture, tinted, filling the square. */
    static CompletableFuture<byte[]> fluid(ResourceLocation id) {
        return OTHER.computeIfAbsent("fluid/" + id, key -> Minecraft.getInstance().submit(() -> {
            net.minecraft.world.level.material.Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
            if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
                return transparent();
            }
            var extensions = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            return sprite(id, extensions.getStillTexture(), extensions.getTintColor());
        }));
    }

    /** A Mekanism chemical as its icon texture, tinted. */
    static CompletableFuture<byte[]> chemical(ResourceLocation id) {
        return OTHER.computeIfAbsent("chemical/" + id, key -> Minecraft.getInstance().submit(() -> {
            if (!io.github.leadassimilator.reticulum.integration.Mods.MEKANISM) {
                return transparent();
            }
            var icon = io.github.leadassimilator.reticulum.integration.mekanism.MekanismCatalog.icon(id);
            return icon == null ? transparent() : sprite(id, icon.texture(), icon.tint());
        }));
    }

    /** A block-atlas texture, tinted (ARGB; alpha 0 counts as opaque), as a 16x16 square. */
    private static byte[] sprite(ResourceLocation id, @javax.annotation.Nullable ResourceLocation texture, int tint) {
        if (texture == null) {
            return transparent();
        }
        var sprite = Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(texture);
        float a = (tint >>> 24) == 0 ? 1f : ((tint >>> 24) & 0xFF) / 255f;
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        return render(id, graphics -> {
            RenderSystem.enableBlend();
            graphics.blit(0, 0, 0, 16, 16, sprite, r, g, b, a);
            RenderSystem.disableBlend();
        });
    }

    /** One offscreen target, reused for every icon (making one per icon is slow when a picker loads dozens). */
    @javax.annotation.Nullable
    private static TextureTarget target;

    private static TextureTarget target() {
        if (target == null) {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        }
        return target;
    }

    private static byte[] render(ResourceLocation id, java.util.function.Consumer<GuiGraphics> draw) {
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
            Reticulum.LOGGER.warn("Could not render icon for {}", id, e);
            return transparent();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static byte[] transparent() {
        try (NativeImage image = new NativeImage(1, 1, true)) {
            image.setPixelRGBA(0, 0, 0);
            return image.asByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
