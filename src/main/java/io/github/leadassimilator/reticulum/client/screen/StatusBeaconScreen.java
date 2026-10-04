package io.github.leadassimilator.reticulum.client.screen;

import java.util.Optional;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Status Beacon: the network it watches (and a button to pick another), what is going off, and per severity what it
 * does — beam, beam through blocks, glow, glow through walls, sound.
 */
public class StatusBeaconScreen extends QuickScreen {
    private static final int WIDTH = 250;
    private static final int ROW = 22;
    private static final int[] OPTIONS = {StatusBeaconBlockEntity.BEAM, StatusBeaconBlockEntity.BEAM_THROUGH, StatusBeaconBlockEntity.GLOW,
            StatusBeaconBlockEntity.GLOW_THROUGH, StatusBeaconBlockEntity.SOUND};
    private static final String[] LABELS = {"beam", "beam_through", "glow", "glow_through", "sound"};

    private final Payloads.OpenBeaconScreen view;
    private int warning;
    private int critical;
    private int left;
    private int top;
    private int gridTop;

    public StatusBeaconScreen(Payloads.OpenBeaconScreen view) {
        super(Component.translatable("block.reticulum.status_beacon"));
        this.view = view;
        this.warning = view.warning();
        this.critical = view.critical();
    }

    @Override
    protected BlockPos block() {
        return view.pos();
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        int height = 64 + Math.max(1, view.alarms().size()) * 11 + 18 + OPTIONS.length * ROW;
        top = Math.max(8, (this.height - height) / 2);
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.pick_network"),
                        b -> PacketDistributor.sendToServer(new Payloads.RequestNetworkPicker(Optional.of(view.pos()))))
                .bounds(left + WIDTH - 80, top + 18, 80, 20).build());
        gridTop = top + 48 + Math.max(1, view.alarms().size()) * 11 + 18;
        for (int row = 0; row < OPTIONS.length; row++) {
            int bit = OPTIONS[row];
            for (int column = 0; column < 2; column++) {
                boolean criticalColumn = column == 1;
                Checkbox box = Checkbox.builder(Component.empty(), font)
                        .pos(left + 150 + column * 56, gridTop + row * ROW)
                        .selected(((criticalColumn ? critical : warning) & bit) != 0)
                        .onValueChange((checkbox, on) -> set(criticalColumn, bit, on))
                        .build();
                box.active = view.canEdit();
                addRenderableWidget(box);
            }
        }
    }

    private void set(boolean criticalColumn, int bit, boolean on) {
        if (criticalColumn) {
            critical = on ? critical | bit : critical & ~bit;
        } else {
            warning = on ? warning | bit : warning & ~bit;
        }
        PacketDistributor.sendToServer(new Payloads.BeaconStyle(view.pos(), warning, critical));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        Component watching = view.network()
                .map(name -> (Component) Component.translatable("screen.reticulum.beacon_watching", name))
                .orElse(Component.translatable("screen.reticulum.beacon_watching_none"));
        graphics.drawString(font, watching, left, top + 24, 0xC0C0C0);
        int y = top + 48;
        if (view.alarms().isEmpty()) {
            graphics.drawString(font, Component.translatable(view.network().isPresent() ? "screen.reticulum.beacon_quiet" : "screen.reticulum.beacon_unbound"),
                    left, y, 0x80C080);
            y += 11;
        }
        for (String alarm : view.alarms()) {
            boolean critical = alarm.startsWith("!");
            graphics.drawString(font, font.plainSubstrByWidth(alarm.substring(1), WIDTH), left, y, critical ? 0xFF5050 : 0xFFC040);
            y += 11;
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_warning"), left + 146, gridTop - 14, 0xFFC040);
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_critical"), left + 202, gridTop - 14, 0xFF5050);
        for (int row = 0; row < OPTIONS.length; row++) {
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_" + LABELS[row]), left, gridTop + row * ROW + 6, 0xE0E0E0);
        }
        if (!view.canEdit()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_read_only"), left, gridTop + OPTIONS.length * ROW + 4, 0xA0A0A0);
        }
    }
}
