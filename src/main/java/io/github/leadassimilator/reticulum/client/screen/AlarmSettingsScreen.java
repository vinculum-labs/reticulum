package io.github.leadassimilator.reticulum.client.screen;

import java.util.Locale;

import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.client.AlarmClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * What the network tool does about alarms, per severity: its mark, its sound from the hotbar (which sound, its pitch,
 * once or two-tone, how often), and its markers in Status mode. Your own settings (the client config), opened by
 * right-clicking with the tool in Status mode.
 */
public class AlarmSettingsScreen extends QuickScreen {
    private static final int WIDTH = 300;
    private static final int ROW = 22;
    private static final String[] LABELS = {"alarm_tool_color", "alarm_tool_sound", "alarm_markers"};
    private static final int COLUMN = 190;

    private int left;
    private int top;
    private int soundsTop;
    /** What is typed in each sound box (0 warning, 1 critical) that isn't the chosen sound: a search. */
    private final String[] search = {"", ""};
    /** Where each severity's sound section starts. */
    private final int[] sectionTop = new int[2];

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

    private static ModConfigSpec.ConfigValue<String> soundId(boolean critical) {
        return critical ? ReticulumClientConfig.TOOL_SOUND_ID_CRITICAL : ReticulumClientConfig.TOOL_SOUND_ID_WARNING;
    }

    private static ModConfigSpec.DoubleValue pitch(boolean critical) {
        return critical ? ReticulumClientConfig.TOOL_SOUND_PITCH_CRITICAL : ReticulumClientConfig.TOOL_SOUND_PITCH_WARNING;
    }

    private static ModConfigSpec.BooleanValue twoTone(boolean critical) {
        return critical ? ReticulumClientConfig.TOOL_SOUND_TWO_TONE_CRITICAL : ReticulumClientConfig.TOOL_SOUND_TWO_TONE_WARNING;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        int results = (search[0].isBlank() ? 0 : 1) + (search[1].isBlank() ? 0 : 1);
        top = Math.max(6, (height - (210 + LABELS.length * ROW + results * SoundSearch.RESULTS * 15)) / 2);
        for (int row = 0; row < LABELS.length; row++) {
            for (int column = 0; column < 2; column++) {
                ModConfigSpec.BooleanValue value = value(row, column == 1);
                addRenderableWidget(Checkbox.builder(Component.empty(), font)
                        .pos(left + COLUMN + 4 + column * 56, top + 48 + row * ROW)
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
        every(left + COLUMN, y, ReticulumClientConfig.TOOL_SOUND_EVERY_WARNING, "every_warning");
        every(left + COLUMN + 56, y, ReticulumClientConfig.TOOL_SOUND_EVERY_CRITICAL, "every_critical");
        soundsTop = y + 26;
        y = soundsTop;
        for (int i = 0; i < 2; i++) {
            y = soundSection(i, y);
        }
    }

    /** One severity's sound: the search box (and its matches), a preview, its pitch and two-tone. Returns where it ends. */
    private int soundSection(int index, int y) {
        boolean critical = index == 1;
        sectionTop[index] = y;
        y += 11;
        String chosen = soundId(critical).get();
        EditBox box = kept("sound_" + index, SoundSearch.box(font, left, y, WIDTH - 24));
        box.setValue(search[index].isEmpty() ? chosen : search[index]);
        box.setResponder(text -> {
            String next = text.equals(chosen) ? "" : text;
            if (!next.equals(search[index])) {
                search[index] = next;
                rebuildKeepingInput();
            }
        });
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.beacon_preview"),
                b -> SoundSearch.preview(soundId(critical).get(), pitch(critical).get().floatValue())).bounds(left + WIDTH - 20, y - 1, 20, 18).build());
        y += 18;
        if (!search[index].isBlank()) {
            for (String id : SoundSearch.matches(search[index])) {
                addRenderableWidget(Button.builder(Component.literal(id), b -> {
                    soundId(critical).set(id);
                    soundId(critical).save();
                    search[index] = "";
                    rebuildWidgets();
                }).bounds(left, y, WIDTH, 14).build());
                y += 15;
            }
        }
        y += 2;
        EditBox pitchBox = kept("pitch_" + index, new SeeThroughTextBox(font, left + 40, y, 40, 16, Component.translatable("screen.reticulum.beacon_pitch")));
        pitchBox.setMaxLength(5);
        pitchBox.setValue(String.format(Locale.ROOT, "%.2f", pitch(critical).get()));
        pitchBox.setResponder(text -> {
            try {
                double value = Double.parseDouble(text.trim());
                if (value >= 0.5 && value <= 2.0) {
                    pitch(critical).set(value);
                    pitch(critical).save();
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(pitchBox);
        addRenderableWidget(Checkbox.builder(Component.translatable("screen.reticulum.beacon_two_tone"), font)
                .pos(left + 100, y - 2).selected(twoTone(critical).getAsBoolean())
                .onValueChange((checkbox, on) -> {
                    twoTone(critical).set(on);
                    twoTone(critical).save();
                })
                .build());
        return y + 26;
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
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_warning"), left + COLUMN, top + 34, 0xFFC040);
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_critical"), left + COLUMN + 56, top + 34, 0xFF5050);
        for (int row = 0; row < LABELS.length; row++) {
            graphics.drawString(font, Component.translatable("screen.reticulum." + LABELS[row]), left, top + 48 + row * ROW + 6, 0xE0E0E0);
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.alarm_sound_every"), left, top + 48 + LABELS.length * ROW + 8, 0xE0E0E0);
        for (int i = 0; i < 2; i++) {
            graphics.drawString(font, Component.translatable(i == 1 ? "screen.reticulum.alarm_sound_critical" : "screen.reticulum.alarm_sound_warning"),
                    left, sectionTop[i], i == 1 ? 0xFF5050 : 0xFFC040);
        }
        // The pitch boxes' labels, beside them.
        for (var child : children()) {
            if (child instanceof EditBox box && box.getMessage().equals(Component.translatable("screen.reticulum.beacon_pitch"))) {
                graphics.drawString(font, Component.translatable("screen.reticulum.beacon_pitch"), left, box.getY() + 4, 0xE0E0E0);
            }
        }
    }
}
