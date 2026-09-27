package io.github.leadassimilator.reticulum.client.screen;

import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.Views;
import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Controller quick screen: network name, access mode, password and a short status. */
public class ControllerScreen extends Screen {
    private static final int WIDTH = 260;

    private Views.ControllerView view;
    private AccessMode mode;
    private EditBox name;
    private EditBox password;
    private Button modeButton;
    private int left;
    private int top;

    public ControllerScreen(Views.ControllerView view) {
        super(Component.translatable("screen.reticulum.controller"));
        this.view = view;
        this.mode = view.mode();
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(Views.ControllerView view) {
        this.view = view;
        this.mode = view.mode();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - 200) / 2);
        name = new EditBox(font, left + 90, top + 20, WIDTH - 90, 18, Component.translatable("screen.reticulum.network_name"));
        name.setMaxLength(64);
        name.setValue(view.networkName());
        name.setEditable(view.canEdit());
        addRenderableWidget(name);

        modeButton = Button.builder(modeLabel(), b -> {
            mode = mode.next();
            b.setMessage(modeLabel());
            password.visible = mode == AccessMode.PASSWORD;
        }).bounds(left + 90, top + 44, WIDTH - 90, 20).build();
        modeButton.active = view.canAdmin();
        addRenderableWidget(modeButton);

        password = new EditBox(font, left + 90, top + 70, WIDTH - 90, 18, Component.translatable("screen.reticulum.password"));
        password.setMaxLength(64);
        password.setHint(Component.translatable(view.hasPassword() ? "screen.reticulum.password_keep" : "screen.reticulum.password_set"));
        password.setFormatter((text, start) -> net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        password.visible = mode == AccessMode.PASSWORD;
        password.setEditable(view.canAdmin());
        addRenderableWidget(password);

        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.open_editor"), b -> {
            onClose();
            io.github.leadassimilator.reticulum.client.web.WebBridge.openEditor(view.networkId());
        }).bounds(left, top + 160, 120, 20).build());

        Button save = Button.builder(Component.translatable("screen.reticulum.save"), b -> save())
                .bounds(left + WIDTH - 80, top + 160, 80, 20).build();
        save.active = view.canEdit() || view.canAdmin();
        addRenderableWidget(save);
    }

    private Component modeLabel() {
        return Component.translatable("screen.reticulum.access." + mode.getSerializedName());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && getFocused() instanceof EditBox) {
            save();
            setFocused(null);
            return true;
        }
        if (ScreenKeys.isInventoryKey(this, keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void save() {
        PacketDistributor.sendToServer(new Payloads.ControllerSettings(view.pos(), name.getValue(), mode, password.getValue()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("screen.reticulum.network_name"), left, top + 25, 0xE0E0E0);
        graphics.drawString(font, Component.translatable("screen.reticulum.access"), left, top + 50, 0xE0E0E0);
        if (password.visible) {
            graphics.drawString(font, Component.translatable("screen.reticulum.password"), left, top + 75, 0xE0E0E0);
        }
        int y = top + 100;
        String owner = view.ownerName().isEmpty() ? "-" : view.ownerName();
        graphics.drawString(font, Component.translatable("screen.reticulum.owner", owner), left, y, 0xC0C0C0);
        graphics.drawString(font, Component.translatable("screen.reticulum.counts", view.connectors(), view.devices()), left, y + 12, 0xC0C0C0);
        if (view.orphans() > 0) {
            graphics.drawString(font, Component.translatable("screen.reticulum.orphans", view.orphans()), left, y + 24, 0xE0B040);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
