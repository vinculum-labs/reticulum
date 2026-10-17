package io.github.leadassimilator.reticulum.client.screen;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.leadassimilator.reticulum.client.ScreenHighlight;
import io.github.leadassimilator.reticulum.network.FaceKey;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.Views;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Quick screen for a connector or a cable: one row per side. Connector rows for devices carry an editable name,
 * an attach/detach switch and a button to name the device's faces (icon buttons); rows for neighboring network blocks connect or disconnect the joint.
 */
public class BlockScreen extends QuickScreen {
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
        rebuildKeepingInput();
    }

    @Override
    protected BlockPos block() {
        return view.pos();
    }

    @Override
    protected void commit() {
        sendRenames();
    }

    @Override
    protected void init() {
        deviceNames.clear();
        connectorName = null;
        left = (width - WIDTH) / 2;
        int top = Math.max(8, (height - (6 * ROW + 90)) / 2);
        int y = top + 24;
        if (view.connector()) {
            connectorNameEditable = view.canEdit() && !view.networkName().isEmpty();
            connectorName = nameBox("connector", left + 90, y, WIDTH - 90, Component.translatable("screen.reticulum.connector_name"), view.customName(),
                    Component.literal(view.defaultName()), connectorNameEditable);
            if (!view.networkName().isEmpty()) {
                // Shrink the name box to make room for the connector's own ID button.
                connectorName.setWidth(connectorName.getWidth() - 26);
                addRenderableWidget(idButton(left + WIDTH - 22, y - 1, view.identified(), Optional.empty(), Optional.empty()));
            }
            y += 26;
        }
        rowsTop = y;
        for (Views.SideView side : view.sides()) {
            addRow(side, y);
            y += ROW;
        }
        if (view.pickHint().isPresent()) {
            // Another network for it: what that does (bind, move its piece, unwire it, or why not) is the tooltip.
            addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.pick_network"), b -> {
                sendRenames();
                ClientPacketDistributor.sendToServer(new Payloads.RequestNetworkPicker(Optional.of(view.pos())));
            }).bounds(width / 2 - 154, y + 8, 150, 20).tooltip(Tooltip.create(view.pickHint().get())).build());
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
                boolean editable = view.canEdit() && side.enabled() && !view.networkName().isEmpty();
                EditBox name = nameBox("device/" + side.side().getSerializedName(), x, y, 186, Component.translatable("screen.reticulum.device_name"),
                        side.customName(), Component.literal(side.defaultName()), editable);
                if (editable) {
                    deviceNames.put(side.side(), name);
                }
                Button toggle = new IconButton(x + 190, y - 1, 20, 20, side.enabled() ? "link_connected" : "link_disconnected",
                        Component.translatable(side.enabled() ? "screen.reticulum.attached" : "screen.reticulum.detached"),
                        Component.translatable(side.enabled() ? "screen.reticulum.attached_tip" : "screen.reticulum.detached_tip"),
                        b -> toggle(side.side()));
                toggle.active = view.canEdit();
                addRenderableWidget(toggle);
                Button faces = new IconButton(x + 214, y - 1, 20, 20, "faces", Component.translatable("screen.reticulum.faces"),
                        Component.translatable("screen.reticulum.faces_tip"), b -> openFaces(side.side()));
                if (!view.networkName().isEmpty() && side.enabled()) {
                    addRenderableWidget(idButton(left + WIDTH - 22, y - 1, side.deviceIdentified(), Optional.of(side.side()),
                            Optional.empty()));
                }
                faces.active = side.enabled() && !view.networkName().isEmpty();
                addRenderableWidget(faces);
            }
            case JOINT -> {
                String joint = side.joint().name().toLowerCase(Locale.ROOT);
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
    private Button idButton(int x, int y, boolean on, Optional<Direction> side, Optional<FaceKey> face) {
        return idButton(this, view.pos(), x, y, on, side, face, view.canUse());
    }

    /** The ID toggle for any of the connector screens; it saves what that screen has typed first. */
    static Button idButton(QuickScreen screen, BlockPos pos, int x, int y, boolean on, Optional<Direction> side,
            Optional<FaceKey> face, boolean canUse) {
        Button button = new IconButton(x, y, 22, 20, on ? "identify_on" : "identify_off", Component.literal("ID"),
                Component.translatable(on ? "screen.reticulum.identify_off" : "screen.reticulum.identify_on"), b -> {
                    screen.commit();
                    ClientPacketDistributor.sendToServer(new Payloads.ToggleIdentify(pos, side, face));
                });
        // Lighting things up takes a user's access (a viewer can only look).
        button.active = canUse;
        return button;
    }

    private void toggle(Direction side) {
        sendRenames();
        ClientPacketDistributor.sendToServer(new Payloads.ToggleSide(view.pos(), side));
    }

    private void openFaces(Direction side) {
        sendRenames();
        minecraft.setScreen(new DeviceFacesScreen(this, view, side));
    }

    /** Sends every editable name that differs from what the server last sent. */
    void sendRenames() {
        if (connectorName != null && connectorNameEditable && !connectorName.getValue().equals(view.customName())) {
            ClientPacketDistributor.sendToServer(new Payloads.RenameConnector(view.pos(), connectorName.getValue()));
        }
        for (Map.Entry<Direction, EditBox> entry : deviceNames.entrySet()) {
            Views.SideView side = view.side(entry.getKey());
            EditBox box = entry.getValue();
            if (!box.getValue().equals(side.customName())) {
                ClientPacketDistributor.sendToServer(new Payloads.RenameDevice(view.pos(), side.side(), Optional.empty(), box.getValue()));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
        ScreenHighlight.set(highlight, null);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = rowsTop - (view.connector() ? 50 : 24);
        renderCompass(graphics, left, WIDTH, top);
        graphics.text(font, title, left, top, 0xFFFFFFFF);
        Component network = view.networkName().isEmpty()
                ? Component.translatable("screen.reticulum.no_network")
                : Component.translatable("screen.reticulum.network", view.networkName());
        graphics.text(font, network, left + WIDTH - font.width(network), top, 0xFFA0A0A0);
        if (view.connector()) {
            graphics.text(font, Component.translatable("screen.reticulum.connector_name"), left, rowsTop - 21, 0xFFE0E0E0);
        }
        int y = rowsTop;
        for (Views.SideView side : view.sides()) {
            graphics.text(font, Component.translatable("screen.reticulum.side." + side.side().getSerializedName()), left, y + 5, 0xFFE0E0E0);
            if (!side.icon().isEmpty()) {
                graphics.item(side.icon(), left + 58, y + 1);
            }
            switch (side.kind()) {
                case JOINT -> graphics.text(font, side.defaultName(), left + 80, y + 5, 0xFFC0C0C0);
                case NONE -> graphics.text(font, Component.translatable("screen.reticulum.empty_side"), left + 80, y + 5, 0xFF707070);
                case DEVICE -> {
                }
            }
            y += ROW;
        }
    }

}
