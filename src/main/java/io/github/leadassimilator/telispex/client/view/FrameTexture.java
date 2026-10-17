package io.github.leadassimilator.telispex.client.view;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * An OpenGL texture made outside the game (a web view's frames) under a name the game can draw. The maker owns the
 * OpenGL name and deletes it; the game's texture manager only refers to it.
 */
final class FrameTexture extends AbstractTexture {
    private FrameTexture(int id) {
        this.id = id;
    }

    /** Makes {@code glTexture} drawable as {@code location} (replacing what was there). */
    static void register(ResourceLocation location, int glTexture) {
        Minecraft.getInstance().getTextureManager().register(location, new FrameTexture(glTexture));
    }

    static void unregister(ResourceLocation location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }

    @Override
    public void load(ResourceManager manager) {
    }

    @Override
    public void releaseId() {
        id = -1;
    }
}
