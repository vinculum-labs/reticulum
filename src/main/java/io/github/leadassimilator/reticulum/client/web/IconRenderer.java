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

    static CompletableFuture<byte[]> item(ResourceLocation id) {
        return CACHE.computeIfAbsent(id, key -> Minecraft.getInstance().submit(() -> render(key)));
    }

    private static byte[] render(ResourceLocation id) {
        ItemStack stack = BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
        if (stack.isEmpty() || stack.is(Items.AIR)) {
            return transparent();
        }
        Minecraft mc = Minecraft.getInstance();
        TextureTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
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
            graphics.renderItem(stack, 0, 0);
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
            target.destroyBuffers();
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
