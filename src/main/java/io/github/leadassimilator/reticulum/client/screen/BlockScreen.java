package io.github.leadassimilator.reticulum.client.screen;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

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

/**
 * Quick screen for a connector or a cable: one row per side. Connector rows for devices carry an editable name,
 * an attach/detach switch and a button to name the device's faces (icon buttons); rows for neighboring network blocks connect or disconnect the joint.
 */
public class BlockScreen extends Screen {
    private static final int WIDTH = 340;
    private static final int ROW = 24;

    private Views.BlockView view;
    private EditBox connectorName;
    private boolean connectorNameEditable;
    private final Map<Direction, EditBox> deviceNames = new EnumMap<>(Direction.class);
    private int left;
    private int rowsTop;

    public BlockScreen(Views.BlockView view) {
        super(Component.translatable(view.connector() ? "screen.reticulum.connector" : "screen.reticulum.cable"));
        this.view = view;
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(Views.BlockView view) {
        this.view = view;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        deviceNames.clear();
        connectorName = null;
        left = (width - WIDTH) / 2;
        int top = Math.max(8, (height - (6 * ROW + 90)) / 2);
        int y = top + 24;
        if (view.connector()) {
            connectorName = new SeeThroughTextBox(font, left + 90, y, WIDTH - 90, 18, Component.translatable("screen.reticulum.connector_name"));
            connectorName.setMaxLength(64);
            connectorName.setValue(view.customName());
            connectorName.setHint(Component.literal(view.defaultName()));
            connectorNameEditable = view.canEdit() && !view.networkName().isEmpty();
            connectorName.setEditable(connectorNameEditable);
            addRenderableWidget(connectorName);
            if (!view.networkName().isEmpty()) {
                // Shrink the name box to make room for the connector's own ID button.
                connectorName.setWidth(connectorName.getWidth() - 26);
                addRenderableWidget(idButton(left + WIDTH - 22, y - 1, view.identified(), java.util.Optional.empty(), java.util.Optional.empty()));
            }
            y += 26;
        }
        rowsTop = y;
        for (Views.SideView side : view.sides()) {
            addRow(side, y);
            y += ROW;
        }
        if (view.bindable()) {
            // No cable decides this connector's network, so it is picked here (a wireless connector).
            addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.pick_network"), b -> {
                sendRenames();
                PacketDistributor.sendToServer(new Payloads.RequestNetworkPicker(java.util.Optional.of(view.pos())));
            }).bounds(width / 2 - 154, y + 8, 150, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(width / 2 + 4, y + 8, 150, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(width / 2 - 75, y + 8, 150, 20).build());
        }
    }

    private void addRow(Views.SideView side, int y) {
        int x = left + 80;
        switch (side.kind()) {
            case DEVICE -> {
                // Name box, then icon buttons: attach/detach, faces, ID.
                EditBox name = new SeeThroughTextBox(font, x, y, 186, 18, Component.translatable("screen.reticulum.device_name"));
                name.setMaxLength(64);
                name.setValue(side.customName());
                name.setHint(Component.literal(side.defaultName()));
                boolean editable = view.canEdit() && side.enabled() && !view.networkName().isEmpty();
                name.setEditable(editable);
                if (editable) {
                    deviceNames.put(side.side(), name);
                }
                addRenderableWidget(name);
                Button toggle = new IconButton(x + 190, y - 1, 20, 20, side.enabled() ? "link_connected" : "link_disconnected",
                        Component.translatable(side.enabled() ? "screen.reticulum.attached" : "screen.reticulum.detached"),
                        Component.translatable(side.enabled() ? "screen.reticulum.attached_tip" : "screen.reticulum.detached_tip"),
                        b -> toggle(side.side()));
                toggle.active = view.canEdit();
                addRenderableWidget(toggle);
                Button faces = new IconButton(x + 214, y - 1, 20, 20, "faces", Component.translatable("screen.reticulum.faces"),
                        Component.translatable("screen.reticulum.faces_tip"), b -> openFaces(side.side()));
                if (!view.networkName().isEmpty() && side.enabled()) {
                    addRenderableWidget(idButton(left + WIDTH - 22, y - 1, side.deviceIdentified(), java.util.Optional.of(side.side()),
                            java.util.Optional.empty()));
                }
                faces.active = side.enabled() && !view.networkName().isEmpty();
                addRenderableWidget(faces);
            }
            case JOINT -> {
                String joint = side.joint().name().toLowerCase(java.util.Locale.ROOT);
                Button toggle = new IconButton(left + WIDTH - 22, y - 1, 22, 20, "link_" + joint,
                        Component.translatable("screen.reticulum.joint." + joint), Component.translatable("screen.reticulum.joint." + joint + "_tip"),
                        b -> toggle(side.side()));
                toggle.active = view.canEdit();
                addRenderableWidget(toggle);
            }
            case NONE -> {
            }
        }
    }

    /** A small ID toggle: lights up the connector, a device or a face in the world until switched off. */
    Button idButton(int x, int y, boolean on, java.util.Optional<Direction> side, java.util.Optional<io.github.leadassimilator.reticulum.network.FaceKey> face) {
        return new IconButton(x, y, 22, 20, on ? "identify_on" : "identify_off", Component.literal("ID"),
                Component.translatable(on ? "screen.reticulum.identify_off" : "screen.reticulum.identify_on"), b -> {
                    sendRenames();
                    PacketDistributor.sendToServer(new Payloads.ToggleIdentify(view.pos(), side, face));
                });
    }

    private void toggle(Direction side) {
        sendRenames();
        PacketDistributor.sendToServer(new Payloads.ToggleSide(view.pos(), side));
    }

    private void openFaces(Direction side) {
        sendRenames();
        minecraft.setScreen(new DeviceFacesScreen(this, view, side));
    }

    /** Sends every editable name that differs from what the server last sent. */
    void sendRenames() {
        if (connectorName != null && connectorNameEditable && !connectorName.getValue().equals(view.customName())) {
            PacketDistributor.sendToServer(new Payloads.RenameConnector(view.pos(), connectorName.getValue()));
        }
        for (Map.Entry<Direction, EditBox> entry : deviceNames.entrySet()) {
            Views.SideView side = view.side(entry.getKey());
            EditBox box = entry.getValue();
            if (!box.getValue().equals(side.customName())) {
                PacketDistributor.sendToServer(new Payloads.RenameDevice(view.pos(), side.side(), Optional.empty(), box.getValue()));
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
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        sendRenames();
        super.onClose();
    }

    /** Dims the world instead of blurring it, so the highlighted block stays visible. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Show in the world what the focused name box names: the connector itself, or the device on that side.
        BlockPos highlight = null;
        if (getFocused() != null && getFocused() == connectorName) {
            highlight = view.pos();
        }
        for (Map.Entry<Direction, EditBox> entry : deviceNames.entrySet()) {
            if (getFocused() == entry.getValue()) {
                highlight = view.pos().relative(entry.getKey());
            }
        }
        io.github.leadassimilator.reticulum.client.ScreenHighlight.set(highlight, null);
        super.render(graphics, mouseX, mouseY, partialTick);
        Compass.render(graphics, font, width - 40, 40, 24);
        int top = rowsTop - (view.connector() ? 50 : 24);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        Component network = view.networkName().isEmpty()
                ? Component.translatable("screen.reticulum.no_network")
                : Component.translatable("screen.reticulum.network", view.networkName());
        graphics.drawString(font, network, left + WIDTH - font.width(network), top, 0xA0A0A0);
        if (view.connector()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.connector_name"), left, rowsTop - 21, 0xE0E0E0);
        }
        int y = rowsTop;
        for (Views.SideView side : view.sides()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.side." + side.side().getSerializedName()), left, y + 5, 0xE0E0E0);
            if (!side.icon().isEmpty()) {
                graphics.renderItem(side.icon(), left + 58, y + 1);
            }
            switch (side.kind()) {
                case JOINT -> graphics.drawString(font, side.defaultName(), left + 80, y + 5, 0xC0C0C0);
                case NONE -> graphics.drawString(font, Component.translatable("screen.reticulum.empty_side"), left + 80, y + 5, 0x707070);
                case DEVICE -> {
                }
            }
            y += ROW;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
