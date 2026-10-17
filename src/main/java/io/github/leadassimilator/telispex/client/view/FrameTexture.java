package io.github.leadassimilator.telispex.client.view;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * A web view's frames, made in an OpenGL texture outside the game, under a name the game can draw. The game can't
 * take in a texture made elsewhere, so it draws one of its own that {@link #copy} brings up to date. The maker owns
 * its OpenGL name and deletes it.
 */
final class FrameTexture extends AbstractTexture {
    /** Framebuffers for the copy, made once. */
    private static int readFramebuffer;
    private static int drawFramebuffer;

    /** The maker's texture. */
    private final int source;
    private final int width;
    private final int height;

    private FrameTexture(int source, int width, int height) {
        this.source = source;
        this.width = width;
        this.height = height;
        texture = RenderSystem.getDevice().createTexture(() -> "Telispex view", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RGBA8, width, height, 1, 1);
        textureView = RenderSystem.getDevice().createTextureView(texture);
        // Smooth when the page is scaled to the screen, and no wrapping at its edges.
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /**
     * Makes {@code glTexture}, {@code width} by {@code height}, drawable as {@code location} (replacing what was there),
     * from a copy: {@link #copy} it after each new frame.
     */
    static FrameTexture register(Identifier location, int glTexture, int width, int height) {
        FrameTexture frame = new FrameTexture(glTexture, width, height);
        Minecraft.getInstance().getTextureManager().register(location, frame);
        return frame;
    }

    static void unregister(Identifier location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }

    /** Copies the maker's texture's current frame into the one the game draws. On the render thread. */
    void copy() {
        // Not once the texture manager has closed it.
        if (texture == null) {
            return;
        }
        if (readFramebuffer == 0) {
            readFramebuffer = GlStateManager.glGenFramebuffers();
            drawFramebuffer = GlStateManager.glGenFramebuffers();
        }
        // Through the game's state tracking, which remembers the bound framebuffers; put back as they were.
        int read = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        int draw = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, source, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                ((GlTexture) texture).glId(), 0);
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        // Let go of both textures, so neither stays attached (the maker deletes its own).
        GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
}
