package io.github.leadassimilator.telispex.client.view;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * An OpenGL texture made outside the game (a web view's frames) under a name the game can draw. The maker owns the
 * OpenGL name and deletes it; the game's texture manager only refers to it.
 */
final class FrameTexture extends AbstractTexture {
    private FrameTexture(int id, int width, int height) {
        // External: closing this texture leaves the OpenGL name to its maker. Format and size are given, not asked of
        // OpenGL: the maker may not have given it storage yet (or OpenGL can't say, for a texture shared with Direct3D).
        this.texture = ((GlDevice) RenderSystem.getDevice()).createExternalTexture("Telispex view " + id, TextureFormat.RGBA8, width, height, 1, id);
    }

    /** Makes {@code glTexture}, {@code width} by {@code height}, drawable as {@code location} (replacing what was there). */
    static void register(ResourceLocation location, int glTexture, int width, int height) {
        Minecraft.getInstance().getTextureManager().register(location, new FrameTexture(glTexture, width, height));
    }

    static void unregister(ResourceLocation location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }
}
