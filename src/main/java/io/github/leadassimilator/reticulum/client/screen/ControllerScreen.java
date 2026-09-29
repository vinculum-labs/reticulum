package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;

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

/**
 * Controller quick screen, in two tabs. Settings: network name, access mode, password and a short summary. Network:
 * the network's on/off button and redstone mode, its sheets with an on/off button each, and the overflow buffer.
 */
public class ControllerScreen extends Screen {
    private static final int WIDTH = 260;

    private Views.ControllerView view;
    private AccessMode mode;
    private EditBox name;
    private EditBox password;
    private Button modeButton;
    private int left;
    private int top;
    /** Which tab is showing; kept when the server sends a fresh view. */
    private boolean networkTab;
    /** First sheet shown in the Network tab's list. */
    private int sheetScroll;
    private static final int SHEET_ROWS = 4;

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
        Button settingsTab = Button.builder(Component.translatable("screen.reticulum.tab_settings"), b -> {
            networkTab = false;
            rebuildWidgets();
        }).bounds(left + WIDTH - 150, top - 4, 74, 16).build();
        Button networkTabButton = Button.builder(Component.translatable("screen.reticulum.tab_network"), b -> {
            networkTab = true;
            rebuildWidgets();
        }).bounds(left + WIDTH - 74, top - 4, 74, 16).build();
        settingsTab.active = networkTab;
        networkTabButton.active = !networkTab;
        addRenderableWidget(settingsTab);
        addRenderableWidget(networkTabButton);
        if (networkTab) {
            initNetworkTab();
            // The settings widgets still exist (hidden) so saving and Enter keep working the same way.
        }
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
        save.visible = !networkTab;
        addRenderableWidget(save);
        if (networkTab) {
            name.visible = false;
            modeButton.visible = false;
            password.visible = false;
        }
    }

    private void initNetworkTab() {
        Button power = Button.builder(Component.translatable(view.enabled() ? "screen.reticulum.on" : "screen.reticulum.off"),
                b -> send(Payloads.ControllerSwitch.NETWORK, java.util.Optional.empty(), view.enabled() ? 0 : 1))
                .bounds(left + 90, top + 20, 60, 20).build();
        power.active = view.canEdit();
        addRenderableWidget(power);
        Button redstone = Button.builder(Component.translatable("screen.reticulum.redstone." + view.redstoneMode().getSerializedName()),
                b -> send(Payloads.ControllerSwitch.REDSTONE, java.util.Optional.empty(), view.redstoneMode().ordinal() + 1))
                .bounds(left + WIDTH - 100, top + 20, 100, 20).build();
        redstone.active = view.canEdit();
        redstone.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.reticulum.redstone_hint")));
        addRenderableWidget(redstone);
        int y = top + 62;
        List<Views.SheetEntry> sheets = view.sheets();
        sheetScroll = Math.max(0, Math.min(sheetScroll, sheets.size() - SHEET_ROWS));
        for (int i = sheetScroll; i < Math.min(sheets.size(), sheetScroll + SHEET_ROWS); i++) {
            Views.SheetEntry sheet = sheets.get(i);
            Button toggle = Button.builder(Component.translatable(sheet.enabled() ? "screen.reticulum.on" : "screen.reticulum.off"),
                    b -> send(Payloads.ControllerSwitch.SHEET, java.util.Optional.of(sheet.id()), sheet.enabled() ? 0 : 1))
                    .bounds(left, y, 36, 16).build();
            toggle.active = view.canEdit();
            addRenderableWidget(toggle);
            y += 18;
        }
    }

    private void send(int what, java.util.Optional<java.util.UUID> sheet, int value) {
        PacketDistributor.sendToServer(new Payloads.ControllerSwitch(view.pos(), what, sheet, value));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (networkTab && view.sheets().size() > SHEET_ROWS) {
            int next = Math.max(0, Math.min(view.sheets().size() - SHEET_ROWS, sheetScroll - (int) Math.signum(scrollY)));
            if (next != sheetScroll) {
                sheetScroll = next;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        if (networkTab) {
            renderNetworkTab(graphics);
            return;
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.network_name"), left, top + 25, 0xE0E0E0);
        graphics.drawString(font, Component.translatable("screen.reticulum.access"), left, top + 50, 0xE0E0E0);
        if (password.visible) {
            graphics.drawString(font, Component.translatable("screen.reticulum.password"), left, top + 75, 0xE0E0E0);
        }
        int y = top + 100;
        String owner = view.ownerName().isEmpty() ? "-" : view.ownerName();
        graphics.drawString(font, Component.translatable("screen.reticulum.owner", owner), left, y, 0xC0C0C0);
        graphics.drawString(font, Component.translatable("screen.reticulum.counts", view.connectors(), view.devices()), left, y + 12, 0xC0C0C0);
        graphics.drawString(font, io.github.leadassimilator.reticulum.item.AntennaItem.range(view.antenna()), left, y + 24, 0xC0C0C0);
        if (view.orphans() > 0) {
            graphics.drawString(font, Component.translatable("screen.reticulum.orphans", view.orphans()), left, y + 36, 0xE0B040);
        }
    }

    private void renderNetworkTab(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.reticulum.network_switch"), left, top + 26, 0xE0E0E0);
        graphics.drawString(font, Component.translatable("screen.reticulum.redstone"), left + WIDTH - 100 - 6 - font.width(Component.translatable("screen.reticulum.redstone")),
                top + 26, 0xE0E0E0);
        Component status = Component.translatable(view.running() ? "screen.reticulum.running" : view.enabled() ? "screen.reticulum.stopped_redstone"
                : "screen.reticulum.stopped_off");
        graphics.drawString(font, status, left, top + 46, view.running() ? 0x70E070 : 0xE0B040);
        int y = top + 62;
        List<Views.SheetEntry> sheets = view.sheets();
        if (sheets.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.no_sheets"), left, y + 4, 0xA0A0A0);
        }
        for (int i = sheetScroll; i < Math.min(sheets.size(), sheetScroll + SHEET_ROWS); i++) {
            Views.SheetEntry sheet = sheets.get(i);
            String marks = (sheet.folderOff() ? "  " + Component.translatable("screen.reticulum.folder_off").getString() : "")
                    + (sheet.redstoneOff() ? "  ⚡" : "") + (sheet.errors() > 0 ? "  ⚠ " + sheet.errors() : "");
            int color = sheet.enabled() && !sheet.folderOff() && !sheet.redstoneOff() ? 0xE0E0E0 : 0x909090;
            graphics.drawString(font, font.plainSubstrByWidth(sheet.name(), WIDTH - 44 - font.width(marks)) + marks, left + 42, y + 4, color);
            y += 18;
        }
        if (sheets.size() > SHEET_ROWS) {
            graphics.drawString(font, (sheetScroll + 1) + "–" + Math.min(sheets.size(), sheetScroll + SHEET_ROWS) + " / " + sheets.size(),
                    left + WIDTH - 40, top + 50, 0xA0A0A0);
        }
        int oy = top + 62 + SHEET_ROWS * 18 + 4;
        if (!view.overflow().isEmpty()) {
            String first = String.join(", ", view.overflow().subList(0, Math.min(2, view.overflow().size())))
                    + (view.overflow().size() > 2 ? " …" : "");
            graphics.drawString(font, Component.translatable("screen.reticulum.overflow", font.plainSubstrByWidth(first, WIDTH - 60)), left, oy, 0xE0B040);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
