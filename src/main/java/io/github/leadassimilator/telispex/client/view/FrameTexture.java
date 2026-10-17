package io.github.leadassimilator.telispex.client.view;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice;

/**
 * An OpenGL texture made outside the game (a web view's frames) under a name the game can draw. The maker owns the
 * OpenGL name and deletes it; the game's texture manager only refers to it.
 */
final class FrameTexture extends AbstractTexture {
    private FrameTexture(int id, int width, int height) {
        // External: closing this texture leaves the OpenGL name to its maker. Format and size are given, not asked of
        // OpenGL: the maker may not have given it storage yet (or OpenGL can't say, for a texture shared with Direct3D).
        // Made on the OpenGL device itself, past NeoForge's validating wrapper (in dev), which only knows its own textures.
        GpuDevice device = RenderSystem.getDevice();
        if (device instanceof ValidationGpuDevice validation) {
            device = validation.getRealDevice();
        }
        this.texture = ((GlDevice) device).createExternalTexture(GpuTexture.USAGE_TEXTURE_BINDING, "Telispex view " + id, TextureFormat.RGBA8,
                width, height, 1, 1, id);
        this.textureView = device.createTextureView(texture);
    }

    /** Makes {@code glTexture}, {@code width} by {@code height}, drawable as {@code location} (replacing what was there). */
    static void register(ResourceLocation location, int glTexture, int width, int height) {
        Minecraft.getInstance().getTextureManager().register(location, new FrameTexture(glTexture, width, height));
    }

    static void unregister(ResourceLocation location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }
}
