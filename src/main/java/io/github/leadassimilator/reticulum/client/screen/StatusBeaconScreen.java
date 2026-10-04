package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;
import java.util.Locale;

import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Status Beacon: its mode, what it shows now, and the look of each state — Warning and Critical in Network mode, each
 * signal level (its own look, or the same as the level below) in Redstone mode. A look is a color, beam and glow
 * switches, and a sound with its pitch, repeat and range.
 */
public class StatusBeaconScreen extends QuickScreen {
    private static final int WIDTH = 300;
    private static final int CELL = 16;
    private static final int SWATCH = 11;
    /** Alarms listed under the status line, at most. */
    private static final int ALARM_LINES = 2;

    private Payloads.OpenBeaconScreen view;
    private CompoundTag settings;
    /** The state being edited: 0 to 15 a signal level, or {@link StatusBeaconBlock#WARNING} / {@link StatusBeaconBlock#CRITICAL}. */
    private int selected;
    private String search = "";
    private int left;
    private int top;
    private int pickerTop;
    private int editorTop;
    /** Where the color row is. */
    private int colorTop;
    private EditBox hex;
    private EditBox label;
    private EditBox pitch;
    private EditBox pitch2;
    private EditBox interval;
    private EditBox range;

    public StatusBeaconScreen(Payloads.OpenBeaconScreen view) {
        super(Component.translatable("block.reticulum.status_beacon"));
        this.view = view;
        this.settings = view.settings().copy();
        this.selected = redstone() ? view.signal() : StatusBeaconBlock.WARNING;
    }

    public BlockPos pos() {
        return view.pos();
    }

    /** New contents from the server (after a mode change): the status and alarms; the settings as the server kept them. */
    public void refresh(Payloads.OpenBeaconScreen next) {
        boolean modeChanged = redstone() != "redstone".equals(next.settings().getString("mode"));
        view = next;
        settings = next.settings().copy();
        if (modeChanged) {
            selected = redstone() ? next.signal() : StatusBeaconBlock.WARNING;
        }
        rebuildKeepingInput();
    }

    @Override
    protected BlockPos block() {
        return view.pos();
    }

    private boolean redstone() {
        return "redstone".equals(settings.getString("mode"));
    }

    // ---------------------------------------------------------------- looks in the settings

    /** The tag of a state's own look, or null when a level takes the one below. */
    private CompoundTag own(int state) {
        if (state == StatusBeaconBlock.WARNING) {
            return settings.getCompound("warning");
        }
        if (state == StatusBeaconBlock.CRITICAL) {
            return settings.getCompound("critical");
        }
        CompoundTag levels = settings.getCompound("levels");
        String key = Integer.toString(state);
        return levels.contains(key) ? levels.getCompound(key) : null;
    }

    /** The look a level shows: its own or the nearest below (null: nothing). */
    private CompoundTag shownAt(int level) {
        for (int i = level; i >= 0; i--) {
            CompoundTag look = own(i);
            if (look != null) {
                return look;
            }
        }
        return null;
    }

    private void setOwn(int level, boolean on) {
        CompoundTag levels = settings.getCompound("levels");
        if (on) {
            CompoundTag below = level > 0 ? shownAt(level - 1) : null;
            levels.put(Integer.toString(level), below != null ? below.copy() : (level >= 8 ? BeaconLook.CRITICAL : BeaconLook.WARNING).save());
        } else {
            levels.remove(Integer.toString(level));
        }
        settings.put("levels", levels);
        send();
        rebuildWidgets();
    }

    private void send() {
        PacketDistributor.sendToServer(new Payloads.BeaconSettings(view.pos(), settings));
    }

    /** Changes the edited look, sends it, and redraws. */
    private void change(java.util.function.Consumer<CompoundTag> edit) {
        CompoundTag look = own(selected);
        if (look == null) {
            return;
        }
        edit.accept(look);
        send();
    }

    // ---------------------------------------------------------------- widgets

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        // Tight, so it fits at large GUI scales: about 225 high at most.
        int alarms = redstone() ? 0 : Math.min(ALARM_LINES, Math.max(1, view.alarms().size()));
        top = Math.max(4, (this.height - 225) / 2);
        String mode = redstone() ? "redstone" : "network";
        Button modeButton = Button.builder(Component.translatable("screen.reticulum.beacon_mode_" + mode), b -> {
                    settings.putString("mode", redstone() ? "network" : "redstone");
                    send();
                })
                .bounds(left, top + 11, WIDTH, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.reticulum.beacon_mode_hint_" + mode)))
                .build();
        modeButton.active = view.canEdit();
        addRenderableWidget(modeButton);
        if (redstone()) {
            pickerTop = top + 46;
            editorTop = pickerTop + CELL + 12;
        } else {
            pickerTop = top + 44 + alarms * 10 + 2;
            addState(StatusBeaconStatePicker.WARNING_LABEL, StatusBeaconBlock.WARNING, left);
            addState(StatusBeaconStatePicker.CRITICAL_LABEL, StatusBeaconBlock.CRITICAL, left + 76);
            editorTop = pickerTop + 20;
        }
        int y = editorTop;
        if (redstone()) {
            boolean ownLook = own(selected) != null;
            Checkbox ownBox = Checkbox.builder(selected == 0 ? Component.translatable("screen.reticulum.beacon_own_first")
                            : Component.translatable("screen.reticulum.beacon_own_level", selected - 1), font)
                    .pos(left, y).selected(ownLook).onValueChange((box, on) -> setOwn(selected, on)).build();
            ownBox.active = view.canEdit();
            addRenderableWidget(ownBox);
            y += 20;
        }
        colorTop = y;
        CompoundTag look = own(selected);
        if (look == null) {
            return;
        }
        BeaconLook current = BeaconLook.load(look, BeaconLook.WARNING);
        // Color: one row of swatches (drawn and clicked by hand, see render and mouseClicked) and the hex box.
        hex = kept("hex", new SeeThroughTextBox(font, left + WIDTH - 52, y, 52, 14, Component.translatable("screen.reticulum.beacon_color")));
        hex.setMaxLength(7);
        hex.setValue(String.format(Locale.ROOT, "#%06X", current.color()));
        hex.setEditable(view.canEdit());
        addRenderableWidget(hex);
        y += 18;
        // The effects: two rows of four switches (green on, gray off).
        int quarter = (WIDTH - 6) / 4;
        int[][] flags = {{BeaconLook.BEAM, BeaconLook.BEAM_THROUGH, BeaconLook.GLOW, BeaconLook.GLOW_THROUGH},
                {BeaconLook.TEXT, BeaconLook.TEXT_THROUGH, BeaconLook.SOUND, BeaconLook.TWO_TONE}};
        String[][] labels = {{"beam", "beam_through", "glow", "glow_through"}, {"text", "text_through", "sound", "two_tone"}};
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 4; i++) {
                toggle(left + i * (quarter + 2), y, quarter, current, flags[row][i], labels[row][i]);
            }
            y += 18;
        }
        y += 2;
        label = kept("label", new SeeThroughTextBox(font, left, y, WIDTH, 14, Component.translatable("screen.reticulum.beacon_label")));
        label.setMaxLength(BeaconLook.LABEL_LIMIT);
        label.setValue(current.label());
        label.setHint(Component.translatable(redstone() ? "screen.reticulum.beacon_label_hint_redstone" : "screen.reticulum.beacon_label_hint_network"));
        label.setEditable(view.canEdit());
        addRenderableWidget(label);
        y += 18;
        String chosen = current.sound().toString();
        EditBox searchBox = kept("search", SoundSearch.box(font, left, y, WIDTH - 22));
        searchBox.setValue(search.isEmpty() ? chosen : search);
        searchBox.setEditable(view.canEdit());
        searchBox.setResponder(text -> {
            // The chosen sound itself isn't a search: no list under it.
            String next = text.equals(chosen) ? "" : text;
            if (!next.equals(search)) {
                search = next;
                rebuildKeepingInput();
            }
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.beacon_preview"), b -> preview())
                .bounds(left + WIDTH - 20, y - 1, 20, 18).build());
        if (!search.isBlank()) {
            List<String> found = SoundSearch.matches(search);
            if (!found.isEmpty()) {
                overlay(new SoundDropdown(font, left, y, 16, WIDTH, height, found, id -> {
                    change(t -> t.putString("sound", id));
                    search = "";
                    rebuildWidgets();
                }));
            }
        }
        y += 20;
        int quarterBox = (WIDTH - 12) / 4;
        pitch = number("pitch", left, y + 10, quarterBox, String.format(Locale.ROOT, "%.2f", current.pitch()));
        pitch2 = number("pitch2", left + quarterBox + 4, y + 10, quarterBox, String.format(Locale.ROOT, "%.2f", current.pitch2()));
        interval = number("interval", left + 2 * (quarterBox + 4), y + 10, quarterBox, Integer.toString(current.interval()));
        range = number("range", left + 3 * (quarterBox + 4), y + 10, quarterBox, Integer.toString(current.range()));
    }

    /** One effect's switch: its name in green while on, gray while off. */
    private void toggle(int x, int y, int width, BeaconLook look, int flag, String labelKey) {
        boolean on = look.has(flag);
        Button button = Button.builder(Component.translatable("screen.reticulum.beacon_" + labelKey).withColor(on ? 0x80FF80 : 0x808080),
                b -> {
                    change(t -> t.putInt("flags", on ? t.getInt("flags") & ~flag : t.getInt("flags") | flag));
                    rebuildKeepingInput();
                }).bounds(x, y, width, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.reticulum.beacon_" + labelKey + "_hint")))
                .build();
        button.active = view.canEdit();
        addRenderableWidget(button);
    }

    private void addState(String label, int state, int x) {
        Button button = Button.builder(Component.translatable(label), b -> {
            commit();
            selected = state;
            rebuildWidgets();
        }).bounds(x, pickerTop, 72, 16).build();
        button.active = selected != state;
        addRenderableWidget(button);
    }

    private EditBox number(String key, int x, int y, int width, String value) {
        EditBox box = kept(key, new SeeThroughTextBox(font, x, y, width, 14, Component.translatable("screen.reticulum.beacon_" + key)));
        box.setMaxLength(6);
        box.setValue(value);
        box.setEditable(view.canEdit());
        addRenderableWidget(box);
        return box;
    }

    private void preview() {
        CompoundTag look = own(selected);
        if (look != null) {
            BeaconLook current = BeaconLook.load(look, BeaconLook.WARNING);
            SoundSearch.preview(current.sound().toString(), current.pitch());
        }
    }

    /** The color, pitch, repeat and range boxes, read on Enter and when leaving. */
    @Override
    protected void commit() {
        CompoundTag look = own(selected);
        if (look == null || hex == null || !view.canEdit()) {
            return;
        }
        boolean changed = false;
        try {
            int color = Integer.parseInt(hex.getValue().replace("#", "").trim(), 16) & 0xFFFFFF;
            changed |= color != look.getInt("color");
            look.putInt("color", color);
        } catch (NumberFormatException ignored) {
        }
        try {
            float value = Float.parseFloat(pitch.getValue().trim());
            changed |= value != look.getFloat("pitch");
            look.putFloat("pitch", value);
        } catch (NumberFormatException ignored) {
        }
        try {
            float value = Float.parseFloat(pitch2.getValue().trim());
            changed |= value != look.getFloat("pitch2");
            look.putFloat("pitch2", value);
        } catch (NumberFormatException ignored) {
        }
        try {
            int value = Integer.parseInt(interval.getValue().trim());
            changed |= value != look.getInt("interval");
            look.putInt("interval", value);
        } catch (NumberFormatException ignored) {
        }
        try {
            int value = Integer.parseInt(range.getValue().trim());
            changed |= value != look.getInt("range");
            look.putInt("range", value);
        } catch (NumberFormatException ignored) {
        }
        String text = label.getValue().strip();
        if (!text.equals(look.getString("label"))) {
            look.putString("label", text);
            changed = true;
        }
        if (changed) {
            send();
        }
    }

    // ---------------------------------------------------------------- drawn by hand: the level strip and the swatches

    private int stripX(int level) {
        return left + level * (CELL + 2);
    }

    private int swatchX(int i) {
        return left + 34 + i * (SWATCH + 2);
    }

    private int swatchY(int i) {
        return colorTop + 1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (redstone() && mouseY >= pickerTop && mouseY < pickerTop + CELL) {
            for (int level = 0; level <= 15; level++) {
                if (mouseX >= stripX(level) && mouseX < stripX(level) + CELL) {
                    commit();
                    selected = level;
                    rebuildWidgets();
                    return true;
                }
            }
        }
        if (own(selected) != null && view.canEdit()) {
            DyeColor[] dyes = DyeColor.values();
            for (int i = 0; i < dyes.length; i++) {
                if (mouseX >= swatchX(i) && mouseX < swatchX(i) + SWATCH && mouseY >= swatchY(i) && mouseY < swatchY(i) + SWATCH) {
                    int color = dyes[i].getTextureDiffuseColor() & 0xFFFFFF;
                    change(t -> t.putInt("color", color));
                    if (hex != null) {
                        hex.setValue(String.format(Locale.ROOT, "#%06X", color));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        graphics.drawString(font, view.status(), left, top + 33, 0xC0C0C0);
        if (!redstone()) {
            int y = top + 44;
            if (view.alarms().isEmpty()) {
                graphics.drawString(font, Component.translatable("screen.reticulum.beacon_quiet"), left, y, 0x80C080);
            }
            for (String alarm : view.alarms().subList(0, Math.min(ALARM_LINES, view.alarms().size()))) {
                boolean critical = alarm.startsWith("!");
                graphics.drawString(font, font.plainSubstrByWidth(alarm.substring(1), WIDTH), left, y, critical ? 0xFF5050 : 0xFFC040);
                y += 10;
            }
        } else {
            // The strip: each level in the color it shows (dark where nothing), the edited one framed, the live one marked.
            for (int level = 0; level <= 15; level++) {
                CompoundTag look = shownAt(level);
                int x = stripX(level);
                int color = look == null ? 0xFF202226 : 0xFF000000 | look.getInt("color");
                if (level == selected) {
                    graphics.fill(x - 1, pickerTop - 1, x + CELL + 1, pickerTop + CELL + 1, 0xFFFFFFFF);
                }
                graphics.fill(x, pickerTop, x + CELL, pickerTop + CELL, color);
                if (own(level) == null && level > 0) {
                    graphics.fill(x, pickerTop + CELL - 3, x + CELL, pickerTop + CELL, 0x80000000);
                }
                String number = Integer.toString(level);
                graphics.drawString(font, number, x + (CELL - font.width(number)) / 2, pickerTop + 5, 0xFFFFFF, true);
                if (level == view.signal()) {
                    graphics.drawString(font, "▲", x + (CELL - font.width("▲")) / 2, pickerTop + CELL + 1, 0xFFFFFF);
                }
            }
        }
        CompoundTag look = own(selected);
        if (redstone() && look == null) {
            CompoundTag below = selected > 0 ? shownAt(selected - 1) : null;
            graphics.drawString(font, below == null ? Component.translatable("screen.reticulum.beacon_shows_nothing")
                    : Component.translatable("screen.reticulum.beacon_same_as", selected - 1), left, colorTop + 4, 0xA0A0A0);
            return;
        }
        if (look == null) {
            return;
        }
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_color"), left, colorTop + 3, 0xE0E0E0);
        DyeColor[] dyes = DyeColor.values();
        for (int i = 0; i < dyes.length; i++) {
            int x = swatchX(i);
            int y = swatchY(i);
            int color = dyes[i].getTextureDiffuseColor() & 0xFFFFFF;
            if (color == look.getInt("color")) {
                graphics.fill(x - 1, y - 1, x + SWATCH + 1, y + SWATCH + 1, 0xFFFFFFFF);
            }
            graphics.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | color);
        }
        if (pitch != null) {
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_pitch"), pitch.getX(), pitch.getY() - 10, 0xE0E0E0);
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_pitch2"), pitch2.getX(), pitch2.getY() - 10, 0xE0E0E0);
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_interval"), interval.getX(), interval.getY() - 10, 0xE0E0E0);
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_range"), range.getX(), range.getY() - 10, 0xE0E0E0);
        }
        if (!view.canEdit()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_read_only"), left, pitch.getY() + 18, 0xA0A0A0);
        }
    }

    /** The labels of Network mode's two states. */
    private static final class StatusBeaconStatePicker {
        static final String WARNING_LABEL = "screen.reticulum.beacon_warning";
        static final String CRITICAL_LABEL = "screen.reticulum.beacon_critical";
    }
}
