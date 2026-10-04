package io.github.leadassimilator.reticulum.client.screen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.entity.NetworkBlockEntity;
import io.github.leadassimilator.reticulum.network.Limits;
import net.minecraft.client.gui.GuiGraphics;
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
        String focused = null;
        for (Map.Entry<String, EditBox> entry : boxes.entrySet()) {
            EditBox box = entry.getValue();
            if (!box.getValue().equals(built.get(entry.getKey()))) {
                typed.put(entry.getKey(), box.getValue());
            }
            if (getFocused() == box) {
                focused = entry.getKey();
            }
        }
        boxes.clear();
        built.clear();
        rebuildWidgets();
        typed.forEach((key, value) -> {
            EditBox box = boxes.get(key);
            if (box != null && box.isActive()) {
                box.setValue(value);
            }
        });
        if (focused != null && boxes.get(focused) != null) {
            setFocused(boxes.get(focused));
        }
    }

    @Override
    protected void rebuildWidgets() {
        boxes.clear();
        built.clear();
        super.rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
        if (enter && getFocused() instanceof EditBox) {
            commit();
            setFocused(null);
            return true;
        }
        if (ScreenKeys.isInventoryKey(this, keyCode, scanCode)) {
            closeAll();
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
                || minecraft.level.getBlockEntity(pos) instanceof io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity)
                || minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > reach() * reach())) {
            // Broken, or walked away from: the server would refuse anything done here now.
            minecraft.setScreen(null);
        }
    }

    /** A little beyond the server's interaction distance, so standing at its edge doesn't flicker the screen shut. */
    private static double reach() {
        try {
            return ReticulumConfig.MAX_INTERACT_DISTANCE.getAsInt() + 2;
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
