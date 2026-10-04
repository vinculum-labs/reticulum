package io.github.leadassimilator.reticulum.client.screen;

import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.client.AlarmClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * What the network tool does about alarms, per severity: its mark, its sound from the hotbar, and its markers in
 * Status mode. Your own settings (the client config), opened by right-clicking the air with the tool in Status mode.
 */
public class AlarmSettingsScreen extends QuickScreen {
    private static final int WIDTH = 250;
    private static final int ROW = 22;
    private static final String[] LABELS = {"alarm_tool_color", "alarm_tool_sound", "alarm_markers"};

    private int left;
    private int top;

    public AlarmSettingsScreen() {
        super(Component.translatable("screen.reticulum.alarm_settings"));
    }

    private static ModConfigSpec.BooleanValue value(int row, boolean critical) {
        return switch (row) {
            case 0 -> critical ? ReticulumClientConfig.TOOL_COLOR_CRITICAL : ReticulumClientConfig.TOOL_COLOR_WARNING;
            case 1 -> critical ? ReticulumClientConfig.TOOL_SOUND_CRITICAL : ReticulumClientConfig.TOOL_SOUND_WARNING;
            default -> critical ? ReticulumClientConfig.MARKERS_CRITICAL : ReticulumClientConfig.MARKERS_WARNING;
        };
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - (90 + LABELS.length * ROW)) / 2);
        for (int row = 0; row < LABELS.length; row++) {
            for (int column = 0; column < 2; column++) {
                ModConfigSpec.BooleanValue value = value(row, column == 1);
                addRenderableWidget(Checkbox.builder(Component.empty(), font)
                        .pos(left + 150 + column * 56, top + 48 + row * ROW)
                        .selected(value.getAsBoolean())
                        .onValueChange((box, on) -> {
                            value.set(on);
                            value.save();
                        })
                        .build());
            }
        }
        // How often the sound plays, per severity, in seconds.
        int y = top + 48 + LABELS.length * ROW + 4;
        every(left + 146, y, ReticulumClientConfig.TOOL_SOUND_EVERY_WARNING, "every_warning");
        every(left + 202, y, ReticulumClientConfig.TOOL_SOUND_EVERY_CRITICAL, "every_critical");
    }

    private void every(int x, int y, ModConfigSpec.IntValue value, String key) {
        EditBox box = kept(key, new SeeThroughTextBox(font, x, y, 40, 16, Component.translatable("screen.reticulum.alarm_sound_every")));
        box.setMaxLength(4);
        box.setValue(Integer.toString(value.getAsInt()));
        box.setResponder(text -> {
            try {
                int seconds = Integer.parseInt(text.trim());
                if (seconds >= 1 && seconds <= 3600 && seconds != value.getAsInt()) {
                    value.set(seconds);
                    value.save();
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        int[] counts = AlarmClient.counts();
        Component now = counts[0] + counts[1] == 0 ? Component.translatable("screen.reticulum.alarm_none")
                : Component.translatable("screen.reticulum.alarm_now", (counts[1] > 0 ? counts[1] + " critical" : "")
                        + (counts[0] > 0 && counts[1] > 0 ? ", " : "") + (counts[0] > 0 ? counts[0] + " warning" : ""));
        graphics.drawString(font, now, left, top + 14, counts[1] > 0 ? 0xFF5050 : counts[0] > 0 ? 0xFFC040 : 0x80C080);
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_warning"), left + 146, top + 34, 0xFFC040);
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_critical"), left + 202, top + 34, 0xFF5050);
        for (int row = 0; row < LABELS.length; row++) {
            graphics.drawString(font, Component.translatable("screen.reticulum." + LABELS[row]), left, top + 48 + row * ROW + 6, 0xE0E0E0);
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.alarm_sound_every"), left, top + 48 + LABELS.length * ROW + 8, 0xE0E0E0);
    }
}
