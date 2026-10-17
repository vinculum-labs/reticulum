package io.github.leadassimilator.telispex.client.view;

import javax.annotation.Nullable;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice;

/**
 * An OpenGL texture made outside the game (a web view's frames) under a name the game can draw. The maker owns the
 * OpenGL name and deletes it; the game's texture manager only refers to it, or to a copy of it the game owns.
 */
final class FrameTexture extends AbstractTexture {
    /** The maker's texture, when the game draws a copy of it; null when it draws the maker's texture itself. */
    @Nullable
    private final GpuTexture source;

    private FrameTexture(int id, int width, int height, boolean copied) {
        // External: closing it leaves the OpenGL name to its maker. Format and size are given, not asked of OpenGL: the
        // maker may not have given it storage yet (or OpenGL can't say, for a texture shared with Direct3D). Made on the
        // OpenGL device itself, past NeoForge's validating wrapper (in dev), which only knows its own textures.
        GlDevice device = device();
        GpuTexture external = device.createExternalTexture(copied ? GpuTexture.USAGE_COPY_SRC : GpuTexture.USAGE_TEXTURE_BINDING,
                "Telispex view " + id, TextureFormat.RGBA8, width, height, 1, 1, id);
        if (copied) {
            source = external;
            texture = device.createTexture(() -> "Telispex view copy", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8, width, height, 1, 1);
            texture.setTextureFilter(FilterMode.LINEAR, false);
            texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
        } else {
            source = null;
            texture = external;
        }
        textureView = device.createTextureView(texture);
    }

    private static GlDevice device() {
        GpuDevice device = RenderSystem.getDevice();
        if (device instanceof ValidationGpuDevice validation) {
            device = validation.getRealDevice();
        }
        return (GlDevice) device;
    }

    /** Makes {@code glTexture}, {@code width} by {@code height}, drawable as {@code location} (replacing what was there). */
    static void register(ResourceLocation location, int glTexture, int width, int height) {
        Minecraft.getInstance().getTextureManager().register(location, new FrameTexture(glTexture, width, height, false));
    }

    /**
     * As {@link #register}, but drawn from a copy the game owns, which {@link #copy} brings up to date. For a texture
     * shared with Direct3D: the game sets up each texture it draws (its mip levels) in ways OpenGL refuses for those.
     */
    static FrameTexture registerCopied(ResourceLocation location, int glTexture, int width, int height) {
        FrameTexture frame = new FrameTexture(glTexture, width, height, true);
        Minecraft.getInstance().getTextureManager().register(location, frame);
        return frame;
    }

    /** Copies the maker's texture's current frame into the copy the game draws (for {@link #registerCopied}). */
    void copy() {
        // Not once the texture manager has closed it (the copy is gone then).
        if (source != null && texture != null) {
            device().createCommandEncoder().copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, source.getWidth(0), source.getHeight(0));
        }
    }

    static void unregister(ResourceLocation location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }

    @Override
    public void close() {
        super.close();
        if (source != null) {
            source.close();
        }
    }
}
