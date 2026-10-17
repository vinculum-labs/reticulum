package io.github.leadassimilator.reticulum.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** Picking a sound from every sound in the game by typing part of its id: the matches, a preview, and the box itself. */
final class SoundSearch {
    /** At most this many matches are listed (the dropdown scrolls through them). */
    static final int RESULTS = 500;
    private static List<String> allSounds;

    private SoundSearch() {
    }

    /** Sound ids containing the text (not ones the server bans), in order, at most {@link #RESULTS}. */
    static List<String> matches(String text) {
        if (allSounds == null) {
            allSounds = BuiltInRegistries.SOUND_EVENT.keySet().stream().map(ResourceLocation::toString).sorted().toList();
        }
        String needle = text.toLowerCase(Locale.ROOT).trim();
        List<String> banned = banned();
        List<String> out = new ArrayList<>();
        for (String id : allSounds) {
            if (id.contains(needle) && !banned.contains(id)) {
                out.add(id);
                if (out.size() == RESULTS) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> banned() {
        try {
            return List.copyOf(ReticulumConfig.BEACON_BANNED_SOUNDS.get());
        } catch (IllegalStateException notLoaded) {
            return List.of();
        }
    }

    /** Plays a sound once, as heard in a menu. */
    static void preview(String id, float pitch) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        SoundEvent sound = location == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(location);
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, 1f));
        }
    }

    /**
     * A search box showing the sound chosen; clicked into, all of it is selected, so typing replaces it and an arrow key
     * keeps it to edit (a long shared prefix needn't be typed again).
     */
    static EditBox box(Font font, int x, int y, int width) {
        EditBox box = new SeeThroughTextBox(font, x, y, width, 16, Component.translatable("screen.reticulum.beacon_search")) {
            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                boolean wasFocused = isFocused();
                boolean used = super.mouseClicked(event, doubleClick);
                if (used && !wasFocused) {
                    moveCursorToEnd(false);
                    setHighlightPos(0);
                }
                return used;
            }
        };
        box.setMaxLength(100);
        box.setHint(Component.translatable("screen.reticulum.beacon_search"));
        return box;
    }
}
