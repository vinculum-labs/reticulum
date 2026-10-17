package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.leadassimilator.reticulum.client.web.WebBridge;
import io.github.leadassimilator.reticulum.item.AntennaItem;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.protocol.Views;
import io.github.leadassimilator.reticulum.security.AccessMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Controller quick screen, in two tabs. Settings: network name, access mode, password and a short summary. Network:
 * the network's on/off button and redstone mode, its sheets with an on/off button each, and the overflow buffer.
 */
public class ControllerScreen extends QuickScreen {
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

    /** A fresh view from the server; what is being typed and not yet saved is kept. */
    public void update(Views.ControllerView view) {
        AccessMode pickedMode = mode != this.view.mode() ? mode : view.mode();
        this.view = view;
        this.mode = pickedMode;
        rebuildKeepingInput();
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - 200) / 2);
        Button settingsTab = Button.builder(Component.translatable("screen.reticulum.tab_settings"), b -> {
            save();
            networkTab = false;
            rebuildWidgets();
        }).bounds(left + WIDTH - 150, top - 4, 74, 16).build();
        Button networkTabButton = Button.builder(Component.translatable("screen.reticulum.tab_network"), b -> {
            save();
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
        name = nameBox("name", left + 90, top + 20, WIDTH - 90, Component.translatable("screen.reticulum.network_name"), view.networkName(),
                Component.empty(), view.canEdit());

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
        password.addFormatter((text, start) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        password.visible = mode == AccessMode.PASSWORD;
        password.setEditable(view.canAdmin());
        addRenderableWidget(kept("password", password));

        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.open_editor"), b -> {
            onClose();
            WebBridge.openEditor(view.networkId());
        }).bounds(left, top + 160, 120, 20).build());

        if (networkTab) {
            name.visible = false;
            modeButton.visible = false;
            password.visible = false;
        }
    }

    private void initNetworkTab() {
        Button power = Button.builder(Component.translatable(view.enabled() ? "screen.reticulum.on" : "screen.reticulum.off"),
                b -> send(Payloads.ControllerSwitch.NETWORK, Optional.empty(), view.enabled() ? 0 : 1))
                .bounds(powerX(), top + 20, POWER_WIDTH, 20).build();
        power.active = view.canEdit();
        addRenderableWidget(power);
        Button redstone = Button.builder(Component.translatable("screen.reticulum.redstone." + view.redstoneMode().getSerializedName()),
                b -> send(Payloads.ControllerSwitch.REDSTONE, Optional.empty(), view.redstoneMode().ordinal() + 1))
                .bounds(redstoneX(), top + 20, left + WIDTH - redstoneX(), 20).build();
        redstone.active = view.canEdit();
        redstone.setTooltip(Tooltip.create(Component.translatable("screen.reticulum.redstone_hint")));
        addRenderableWidget(redstone);
        int y = top + 62;
        List<Views.SheetEntry> sheets = view.sheets();
        sheetScroll = Math.max(0, Math.min(sheetScroll, sheets.size() - SHEET_ROWS));
        for (int i = sheetScroll; i < Math.min(sheets.size(), sheetScroll + SHEET_ROWS); i++) {
            Views.SheetEntry sheet = sheets.get(i);
            Button toggle = Button.builder(Component.translatable(sheet.enabled() ? "screen.reticulum.on" : "screen.reticulum.off"),
                    b -> send(Payloads.ControllerSwitch.SHEET, Optional.of(sheet.id()), sheet.enabled() ? 0 : 1))
                    .bounds(left + indent(sheet), y, 36, 16).build();
            toggle.active = view.canEdit();
            addRenderableWidget(toggle);
            y += 18;
        }
    }

    /** Sheets inside others sit further right, by how deep they are (capped, so deep trees still fit). */
    private static int indent(Views.SheetEntry sheet) {
        return Math.min(sheet.depth(), 6) * 8;
    }

    private void send(int what, Optional<UUID> sheet, int value) {
        ClientPacketDistributor.sendToServer(new Payloads.ControllerSwitch(view.pos(), what, sheet, value));
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
    protected BlockPos block() {
        return view.pos();
    }

    @Override
    protected void commit() {
        save();
    }

    /** Sends the settings when they changed; called on Enter, on switching tabs and on closing (there is no Save button). */
    private void save() {
        if (name == null || !(view.canEdit() || view.canAdmin())) {
            return;
        }
        if (name.getValue().equals(view.networkName()) && mode == view.mode() && password.getValue().isEmpty()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new Payloads.ControllerSettings(view.pos(), name.getValue(), mode, password.getValue()));
        password.setValue("");
    }


    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, top, 0xFFFFFFFF);
        if (networkTab) {
            renderNetworkTab(graphics);
            return;
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.network_name"), left, top + 25, 0xFFE0E0E0);
        graphics.drawString(font, Component.translatable("screen.reticulum.access"), left, top + 50, 0xFFE0E0E0);
        if (password.visible) {
            graphics.drawString(font, Component.translatable("screen.reticulum.password"), left, top + 75, 0xFFE0E0E0);
        }
        int y = top + 100;
        String owner = view.ownerName().isEmpty() ? "-" : view.ownerName();
        graphics.drawString(font, Component.translatable("screen.reticulum.owner", owner), left, y, 0xFFC0C0C0);
        graphics.drawString(font, Component.translatable("screen.reticulum.counts", view.connectors(), view.devices()), left, y + 12, 0xFFC0C0C0);
        graphics.drawString(font, AntennaItem.range(view.antenna()), left, y + 24, 0xFFC0C0C0);
        if (!view.power().isEmpty()) {
            graphics.drawString(font, view.power(), left, y + 48, 0xFFC0C0C0);
        }
        if (view.orphans() > 0) {
            graphics.drawString(font, Component.translatable("screen.reticulum.orphans", view.orphans()), left, y + 36, 0xFFE0B040);
        }
    }

    // The Network row, left to right: label, on/off button, label, redstone button; placed by the text widths.
    private static final int POWER_WIDTH = 50;
    private static final int GAP = 6;

    private int powerX() {
        return left + font.width(Component.translatable("screen.reticulum.network_switch")) + GAP;
    }

    private int redstoneLabelX() {
        return powerX() + POWER_WIDTH + 2 * GAP;
    }

    private int redstoneX() {
        return redstoneLabelX() + font.width(Component.translatable("screen.reticulum.redstone")) + GAP;
    }

    private void renderNetworkTab(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.reticulum.network_switch"), left, top + 26, 0xFFE0E0E0);
        graphics.drawString(font, Component.translatable("screen.reticulum.redstone"), redstoneLabelX(), top + 26, 0xFFE0E0E0);
        Component status = Component.translatable(view.running() ? "screen.reticulum.running" : view.enabled() ? "screen.reticulum.stopped_redstone"
                : "screen.reticulum.stopped_off");
        graphics.drawString(font, status, left, top + 46, view.running() ? 0xFF70E070 : 0xFFE0B040);
        int y = top + 62;
        List<Views.SheetEntry> sheets = view.sheets();
        if (sheets.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.no_sheets"), left, y + 4, 0xFFA0A0A0);
        }
        for (int i = sheetScroll; i < Math.min(sheets.size(), sheetScroll + SHEET_ROWS); i++) {
            Views.SheetEntry sheet = sheets.get(i);
            String marks = (sheet.parentOff() ? "  " + Component.translatable("screen.reticulum.parent_off").getString() : "")
                    + (sheet.gateOff() ? "  ⚡" : "") + (sheet.errors() > 0 ? "  ⚠ " + sheet.errors() : "");
            int color = sheet.enabled() && !sheet.parentOff() && !sheet.gateOff() ? 0xFFE0E0E0 : 0xFF909090;
            int x = left + 42 + indent(sheet);
            graphics.drawString(font, font.plainSubstrByWidth(sheet.name(), left + WIDTH - x - font.width(marks)) + marks, x, y + 4, color);
            y += 18;
        }
        if (sheets.size() > SHEET_ROWS) {
            graphics.drawString(font, (sheetScroll + 1) + "–" + Math.min(sheets.size(), sheetScroll + SHEET_ROWS) + " / " + sheets.size(),
                    left + WIDTH - 40, top + 50, 0xFFA0A0A0);
        }
        int oy = top + 62 + SHEET_ROWS * 18 + 4;
        if (!view.overflow().isEmpty()) {
            String first = String.join(", ", view.overflow().subList(0, Math.min(2, view.overflow().size())))
                    + (view.overflow().size() > 2 ? " …" : "");
            graphics.drawString(font, Component.translatable("screen.reticulum.overflow", font.plainSubstrByWidth(first, WIDTH - 60)), left, oy, 0xFFE0B040);
        }
    }

}
