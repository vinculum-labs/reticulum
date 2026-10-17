package io.github.leadassimilator.reticulum.client.screen;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import io.github.leadassimilator.reticulum.client.ScreenHighlight;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/** Names the faces (six sides plus unsided) of one device reached by a connector. */
public class DeviceFacesScreen extends QuickScreen {
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
    protected void closeAll() {
        // Everything, not just back to the connector screen.
        sendRenames();
        parent.commit();
        minecraft.setScreen(null);
    }

    @Override
    protected void init() {
        faces.clear();
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - (FaceKey.values().length * ROW + 70)) / 2);
        Views.SideView device = view.side(side);
        int y = top + 30;
        for (FaceKey face : FaceKey.values()) {
            if (face.direction() != null) {
                addRenderableWidget(BlockScreen.idButton(this, view.pos(), left + WIDTH - 22, y - 1, device.faceIdentified(face), Optional.of(side),
                        Optional.of(face), view.canUse()));
            }
            Component name = Component.translatable("screen.reticulum.face." + face.getSerializedName());
            faces.put(face, nameBox("face/" + face.getSerializedName(), left + 80, y, WIDTH - 80 - 26, name, device.faceName(face), name, view.canEdit()));
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
                ClientPacketDistributor.sendToServer(new Payloads.RenameDevice(view.pos(), side, Optional.of(entry.getKey()), box.getValue()));
            }
        }
    }

    @Override
    public void onClose() {
        sendRenames();
        minecraft.setScreen(parent);
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
        ScreenHighlight.set(devicePos, focusedFace);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCompass(graphics, left, WIDTH, top);
        Views.SideView device = view.side(side);
        graphics.drawString(font, title, left, top, 0xFFFFFFFF);
        if (!device.icon().isEmpty()) {
            graphics.renderItem(device.icon(), left, top + 10);
        }
        graphics.drawString(font, device.displayName(), left + 20, top + 14, 0xFFC0C0C0);
        int y = top + 30;
        for (FaceKey face : FaceKey.values()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.face." + face.getSerializedName()), left, y + 5, 0xFFE0E0E0);
            y += ROW;
        }
    }

}
