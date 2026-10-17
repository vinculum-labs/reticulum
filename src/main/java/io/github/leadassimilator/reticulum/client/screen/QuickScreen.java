package io.github.leadassimilator.reticulum.client.screen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.network.Limits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * What every Reticulum screen shares, so they behave alike: Enter in a text box saves and leaves it (on a button,
 * Enter presses it); the inventory key closes the screen unless you're typing; what you typed is saved before any
 * action and when leaving, and survives the server refreshing the screen; the screen closes once its block is gone
 * or out of reach; the game keeps running behind it, dimmed rather than blurred.
 */
public abstract class QuickScreen extends Screen {
    /** Name boxes by a key stable across rebuilds, and the value each showed when built. */
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    private final Map<String, String> built = new HashMap<>();

    protected QuickScreen(Component title) {
        super(title);
    }

    /** Sends what was typed that differs from what the server has. Called on Enter, before actions and on leaving. */
    protected void commit() {
    }

    /** The block this screen is about; the screen closes once it is gone or out of reach. Null: about no block. */
    @Nullable
    protected BlockPos block() {
        return null;
    }

    /** A name box, at most {@link Limits#NAME} long, kept under {@code key} so what is typed survives a refresh. */
    protected EditBox nameBox(String key, int x, int y, int width, Component label, String value, Component hint, boolean editable) {
        EditBox box = new SeeThroughTextBox(font, x, y, width, 18, label);
        box.setMaxLength(Limits.NAME);
        box.setValue(value);
        box.setHint(hint);
        box.setEditable(editable);
        boxes.put(key, box);
        built.put(key, value);
        addRenderableWidget(box);
        return box;
    }

    /** Keeps what is typed into any box (not only names) across a refresh, under {@code key}. */
    protected <T extends EditBox> T kept(String key, T box) {
        boxes.put(key, box);
        built.put(key, box.getValue());
        return box;
    }

    /** Rebuilds the widgets for new values from the server, keeping what the player was typing and which box had focus. */
    protected void rebuildKeepingInput() {
        Map<String, String> typed = new HashMap<>();
        Map<String, Integer> cursors = new HashMap<>();
        String focused = null;
        for (Map.Entry<String, EditBox> entry : boxes.entrySet()) {
            EditBox box = entry.getValue();
            if (!box.getValue().equals(built.get(entry.getKey()))) {
                typed.put(entry.getKey(), box.getValue());
            }
            cursors.put(entry.getKey(), box.getCursorPosition());
            if (getFocused() == box) {
                focused = entry.getKey();
            }
        }
        boxes.clear();
        built.clear();
        rebuildWidgets();
        // What the rebuilt boxes show before what was typed goes back in: that is what they were built with.
        boxes.forEach((key, box) -> built.put(key, box.getValue()));
        builtRead = true;
        typed.forEach((key, value) -> {
            EditBox box = boxes.get(key);
            if (box != null && box.isActive()) {
                box.setValue(value);
            }
        });
        if (focused != null && boxes.get(focused) != null) {
            setFocused(boxes.get(focused));
        }
        // The cursor back where it was (setting a box's text puts it at the end), so typing mid-word goes on there.
        cursors.forEach((key, cursor) -> {
            EditBox box = boxes.get(key);
            if (box != null) {
                box.setCursorPosition(Math.min(cursor, box.getValue().length()));
                box.setHighlightPos(box.getCursorPosition());
            }
        });
    }

    /** Resized (the window, the GUI scale): laid out again, keeping what was being typed. */
    @Override
    protected void repositionElements() {
        rebuildKeepingInput();
    }

    /** Whether {@link #built} has been read from the boxes since they were made (they are kept before their value is set). */
    private boolean builtRead;

    @Override
    protected void rebuildWidgets() {
        boxes.clear();
        built.clear();
        builtRead = false;
        overlay = null;
        pendingOverlay = null;
        super.rebuildWidgets();
    }

    /** A widget over the others (a dropdown): drawn after them, and given clicks and scrolls before them. */
    @Nullable
    private AbstractWidget overlay;
    @Nullable
    private AbstractWidget pendingOverlay;

    /** The overlay was closed with Esc: forget what made it (a search), so the next rebuild doesn't bring it back. */
    protected void overlayDismissed() {
    }

    /** Sets the overlay; it is added once {@link #init} is done, so it comes after (over) everything else. */
    protected void overlay(AbstractWidget widget) {
        pendingOverlay = widget;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!builtRead) {
            boxes.forEach((key, box) -> built.put(key, box.getValue()));
            builtRead = true;
        }
        if (pendingOverlay != null) {
            overlay = pendingOverlay;
            pendingOverlay = null;
            addRenderableWidget(overlay);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overlayClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** What the boxes show now was sent: it no longer counts as typed, so the server's answer replaces it. */
    protected void markSent() {
        boxes.forEach((key, box) -> built.put(key, box.getValue()));
    }

    /** Hands a click to the overlay; a screen handling clicks itself calls this first, so the overlay stays on top. */
    protected boolean overlayClicked(double mouseX, double mouseY, int button) {
        return overlay != null && overlay.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overlay != null && overlay.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && getFocused() instanceof EditBox) {
            commit();
            setFocused(null);
            return true;
        }
        if (ScreenKeys.isInventoryKey(this, keyCode, scanCode)) {
            closeAll();
            return true;
        }
        // Esc with a dropdown open closes the dropdown, not the screen.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && overlay != null) {
            removeWidget(overlay);
            overlay = null;
            overlayDismissed();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Leaves every Reticulum screen at once (the inventory key), saving first. */
    protected void closeAll() {
        commit();
        minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        commit();
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        BlockPos pos = block();
        if (pos != null && minecraft.player != null && minecraft.level != null && (!(minecraft.level.getBlockEntity(pos) instanceof NetworkBlockEntity
                || minecraft.level.getBlockEntity(pos) instanceof StatusBeaconBlockEntity)
                || minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > reach() * reach())) {
            // Broken, or walked away from: the server would refuse anything done here now.
            minecraft.setScreen(null);
        }
    }

    /** The server's interaction distance: past it, the server refuses what the screen sends, so it closes. */
    private static double reach() {
        try {
            return ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt();
        } catch (IllegalStateException notLoaded) {
            return 10;
        }
    }

    /** Dims the world instead of blurring it, so what the screen highlights in the world stays visible. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    /** The compass, beside the panel where there is room (never over its buttons), or not at all. */
    protected void renderCompass(GuiGraphics graphics, int panelLeft, int panelWidth, int panelTop) {
        int radius = 24;
        int right = panelLeft + panelWidth;
        if (width - right >= 2 * radius + 16) {
            Compass.render(graphics, font, right + radius + 8, panelTop + radius, radius);
        } else if (panelLeft >= 2 * radius + 16) {
            Compass.render(graphics, font, panelLeft - radius - 8, panelTop + radius, radius);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
