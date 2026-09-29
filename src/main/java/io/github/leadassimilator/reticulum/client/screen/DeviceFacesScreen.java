package io.github.leadassimilator.reticulum.client.screen;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.Views;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Names the faces (six sides plus unsided) of one device reached by a connector. */
public class DeviceFacesScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int ROW = 22;

    private final BlockScreen parent;
    private final Direction side;
    private Views.BlockView view;
    private final Map<FaceKey, EditBox> faces = new EnumMap<>(FaceKey.class);
    private int left;
    private int top;

    public DeviceFacesScreen(BlockScreen parent, Views.BlockView view, Direction side) {
        super(Component.translatable("screen.reticulum.faces_title"));
        this.parent = parent;
        this.view = view;
        this.side = side;
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(Views.BlockView view) {
        this.view = view;
        parent.update(view);
        rebuildWidgets();
    }

    @Override
    protected void init() {
        faces.clear();
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - (FaceKey.values().length * ROW + 70)) / 2);
        Views.SideView device = view.side(side);
        int y = top + 30;
        for (FaceKey face : FaceKey.values()) {
            EditBox box = new SeeThroughTextBox(font, left + 80, y, WIDTH - 80 - 26, 18, Component.translatable("screen.reticulum.face." + face.getSerializedName()));
            if (face.direction() != null) {
                addRenderableWidget(parent.idButton(left + WIDTH - 22, y - 1, device.faceIdentified(face), java.util.Optional.of(side), java.util.Optional.of(face)));
            }
            box.setMaxLength(64);
            box.setValue(device.faceName(face));
            box.setHint(Component.translatable("screen.reticulum.face." + face.getSerializedName()));
            box.setEditable(view.canEdit());
            faces.put(face, box);
            addRenderableWidget(box);
            y += ROW;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 75, y + 6, 150, 20).build());
    }

    private void sendRenames() {
        Views.SideView device = view.side(side);
        for (Map.Entry<FaceKey, EditBox> entry : faces.entrySet()) {
            EditBox box = entry.getValue();
            if (view.canEdit() && !box.getValue().equals(device.faceName(entry.getKey()))) {
                PacketDistributor.sendToServer(new Payloads.RenameDevice(view.pos(), side, Optional.of(entry.getKey()), box.getValue()));
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            // Enter commits the edit and leaves the text box, like pressing Done on a form field.
            sendRenames();
            setFocused(null);
            return true;
        }
        if (ScreenKeys.isInventoryKey(this, keyCode, scanCode)) {
            // Close everything, not just back to the connector screen.
            sendRenames();
            parent.sendRenames();
            minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        sendRenames();
        minecraft.setScreen(parent);
    }

    /** Dims the world instead of blurring it, so the highlighted face stays visible. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The device stays outlined; the face whose name box has focus is filled in (unsided: the whole device).
        BlockPos devicePos = view.pos().relative(side);
        Direction focusedFace = null;
        for (Map.Entry<FaceKey, EditBox> entry : faces.entrySet()) {
            if (getFocused() == entry.getValue()) {
                focusedFace = entry.getKey().direction();
            }
        }
        io.github.leadassimilator.reticulum.client.ScreenHighlight.set(devicePos, focusedFace);
        super.render(graphics, mouseX, mouseY, partialTick);
        Compass.render(graphics, font, width - 40, 40, 24);
        Views.SideView device = view.side(side);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        if (!device.icon().isEmpty()) {
            graphics.renderItem(device.icon(), left, top + 10);
        }
        graphics.drawString(font, device.displayName(), left + 20, top + 14, 0xC0C0C0);
        int y = top + 30;
        for (FaceKey face : FaceKey.values()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.face." + face.getSerializedName()), left, y + 5, 0xE0E0E0);
            y += ROW;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
