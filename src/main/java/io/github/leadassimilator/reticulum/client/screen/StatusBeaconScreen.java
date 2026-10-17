package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
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
    /** Where the dye swatches start, from the left edge (the last ends just short of the hex box). */
    private static final int SWATCHES_LEFT = 38;
    /** Alarms listed under the status line, at most. */
    private static final int ALARM_LINES = 2;

    private Payloads.OpenBeaconScreen view;
    private CompoundTag settings;
    /** The state being edited: 0 to 15 a signal level, or {@link StatusBeaconBlock#WARNING} / {@link StatusBeaconBlock#CRITICAL}. */
    private int selected;
    private String search = "";

    @Override
    protected void overlayDismissed() {
        search = "";
    }
    private int left;
    private int top;
    private int pickerTop;
    private int editorTop;
    /** Where the color row is. */
    private int colorTop;
    private EditBox hex;
    private EditBox label;
    private EditBox textColor;
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
        // Each change sends the whole settings: an answer to an older one would put back what a newer one changed, so
        // only the answer to the last one sent is taken.
        // (A change the server refused gets no answer: after a moment, any answer is taken again.)
        if (inFlight > 1 && System.currentTimeMillis() - lastSent < 2000) {
            inFlight--;
            return;
        }
        inFlight = 0;
        // Against what the server had before: the local copy already took the new mode when its button was pressed.
        boolean modeChanged = !view.settings().getStringOr("mode", "").equals(next.settings().getStringOr("mode", ""));
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
            return settings.getCompoundOrEmpty("warning");
        }
        if (state == StatusBeaconBlock.CRITICAL) {
            return settings.getCompoundOrEmpty("critical");
        }
        CompoundTag levels = settings.getCompoundOrEmpty("levels");
        String key = Integer.toString(state);
        return levels.contains(key) ? levels.getCompoundOrEmpty(key) : null;
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
        CompoundTag levels = settings.getCompoundOrEmpty("levels");
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
        inFlight++;
        lastSent = System.currentTimeMillis();
        ClientPacketDistributor.sendToServer(new Payloads.BeaconSettings(view.pos(), settings));
    }

    /** Settings sent whose answer hasn't come back yet, and when the last went. */
    private int inFlight;
    private long lastSent;

    /** Changes the edited look, sends it, and redraws. */
    private void change(Consumer<CompoundTag> edit) {
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
        // Tight, so it fits at large GUI scales: about 243 high at most.
        int alarms = redstone() ? 0 : Math.min(ALARM_LINES, Math.max(1, view.alarms().size()));
        top = Math.max(4, (this.height - 243) / 2);
        String mode = redstone() ? "redstone" : "network";
        Button modeButton = Button.builder(Component.translatable("screen.reticulum.beacon_mode_" + mode), b -> {
                    // What was typed goes into the look it was typed for first (the boxes then show another look).
                    commit();
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
        // The text's color: the same row (swatches, hex box), with "automatic" first (blank box).
        textColor = kept("text_color", new SeeThroughTextBox(font, left + WIDTH - 52, y, 52, 14, Component.translatable("screen.reticulum.beacon_text_color")));
        textColor.setMaxLength(7);
        textColor.setValue(current.textColor() == BeaconLook.AUTO ? "" : String.format(Locale.ROOT, "#%06X", current.textColor()));
        textColor.setHint(Component.translatable("screen.reticulum.beacon_text_color_auto").withStyle(s -> s.withColor(0x808080)));
        textColor.setTooltip(Tooltip.create(Component.translatable("screen.reticulum.beacon_text_color_hint")));
        textColor.setEditable(view.canEdit());
        addRenderableWidget(textColor);
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
                    // What was typed is saved first: the rebuild shows what is saved.
                    commit();
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

    /** One effect's switch: ● and green while on, ○ and light gray while off. */
    private void toggle(int x, int y, int width, BeaconLook look, int flag, String labelKey) {
        boolean on = look.has(flag);
        // A mark as well as a color: dim text on the gray button was hard to read.
        Button button = Button.builder(Component.literal(on ? "● " : "○ ").append(Component.translatable("screen.reticulum.beacon_" + labelKey))
                        .withColor(on ? 0x80FF80 : 0xD8D8D8),
                b -> {
                    change(t -> t.putInt("flags", on ? t.getIntOr("flags", 0) & ~flag : t.getIntOr("flags", 0) | flag));
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
            changed |= color != look.getIntOr("color", 0);
            look.putInt("color", color);
        } catch (NumberFormatException ignored) {
        }
        if (textColor != null) {
            String text = textColor.getValue().replace("#", "").trim();
            try {
                int color = text.isEmpty() ? BeaconLook.AUTO : Integer.parseInt(text, 16) & 0xFFFFFF;
                int before = look.contains("text_color") ? look.getIntOr("text_color", 0) : BeaconLook.AUTO;
                changed |= color != before;
                look.putInt("text_color", color);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            float value = Float.parseFloat(pitch.getValue().trim().replace(',', '.'));
            if (Float.isFinite(value)) {
                changed |= value != look.getFloatOr("pitch", 0f);
                look.putFloat("pitch", value);
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            float value = Float.parseFloat(pitch2.getValue().trim().replace(',', '.'));
            if (Float.isFinite(value)) {
                changed |= value != look.getFloatOr("pitch2", 0f);
                look.putFloat("pitch2", value);
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            int value = Integer.parseInt(interval.getValue().trim());
            changed |= value != look.getIntOr("interval", 0);
            look.putInt("interval", value);
        } catch (NumberFormatException ignored) {
        }
        try {
            int value = Integer.parseInt(range.getValue().trim());
            changed |= value != look.getIntOr("range", 0);
            look.putInt("range", value);
        } catch (NumberFormatException ignored) {
        }
        String text = label.getValue().strip();
        if (!text.equals(look.getStringOr("label", ""))) {
            look.putString("label", text);
            changed = true;
        }
        if (changed) {
            send();
            markSent();
        }
    }

    // ---------------------------------------------------------------- drawn by hand: the level strip and the swatches

    private int stripX(int level) {
        return left + level * (CELL + 2);
    }

    private int swatchX(int i) {
        // Room before them for a row's name and the text row's "automatic" swatch.
        return left + SWATCHES_LEFT + i * (SWATCH + 2);
    }

    /** Row 0: the color; row 1: the text's color. */
    private int swatchY(int row) {
        return colorTop + 1 + row * 18;
    }

    /** The color the state being edited has by default: amber for warnings (levels 0 to 7), red for critical (8 to 15). */
    private int defaultColor() {
        boolean critical = selected == StatusBeaconBlock.CRITICAL || selected >= 8 && selected <= 15;
        return (critical ? BeaconLook.CRITICAL : BeaconLook.WARNING).color();
    }

    /** The frame round a chosen swatch: white, or amber round a light one (white on white wouldn't show). */
    private static int ring(int color) {
        int luma = (((color >> 16) & 0xFF) * 299 + ((color >> 8) & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
        return luma > 200 ? 0xFFFFC040 : 0xFFFFFFFF;
    }

    /** Each row's "automatic" swatch, just before the dyes. */
    private int autoX() {
        return left + SWATCHES_LEFT - (SWATCH + 2);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (overlayClicked(event, doubleClick)) {
            return true;
        }
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
            for (int row = 0; row < 2; row++) {
                String key = row == 0 ? "color" : "text_color";
                EditBox box = row == 0 ? hex : textColor;
                if (mouseY < swatchY(row) || mouseY >= swatchY(row) + SWATCH) {
                    continue;
                }
                if (mouseX >= autoX() && mouseX < autoX() + SWATCH) {
                    // The color back to this state's usual one; the text's to automatic (blank box).
                    int value = row == 0 ? defaultColor() : BeaconLook.AUTO;
                    change(t -> t.putInt(key, value));
                    if (box != null) {
                        box.setValue(row == 0 ? String.format(Locale.ROOT, "#%06X", value) : "");
                    }
                    return true;
                }
                for (int i = 0; i < dyes.length; i++) {
                    if (mouseX >= swatchX(i) && mouseX < swatchX(i) + SWATCH) {
                        int color = dyes[i].getTextureDiffuseColor() & 0xFFFFFF;
                        change(t -> t.putInt(key, color));
                        if (box != null) {
                            box.setValue(String.format(Locale.ROOT, "#%06X", color));
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, title, left, top, 0xFFFFFFFF);
        graphics.text(font, view.status(), left, top + 33, 0xFFC0C0C0);
        if (!redstone()) {
            int y = top + 44;
            if (view.alarms().isEmpty()) {
                graphics.text(font, Component.translatable("screen.reticulum.beacon_quiet"), left, y, 0xFF80C080);
            }
            for (String alarm : view.alarms().subList(0, Math.min(ALARM_LINES, view.alarms().size()))) {
                boolean critical = alarm.startsWith("!");
                graphics.text(font, font.plainSubstrByWidth(alarm.substring(1), WIDTH), left, y, critical ? 0xFFFF5050 : 0xFFFFC040);
                y += 10;
            }
        } else {
            // The strip: each level in the color it shows (dark where nothing), the edited one framed, the live one marked.
            for (int level = 0; level <= 15; level++) {
                CompoundTag look = shownAt(level);
                int x = stripX(level);
                int color = look == null ? 0xFF202226 : 0xFF000000 | look.getIntOr("color", 0);
                if (level == selected) {
                    graphics.fill(x - 1, pickerTop - 1, x + CELL + 1, pickerTop + CELL + 1, 0xFFFFFFFF);
                }
                graphics.fill(x, pickerTop, x + CELL, pickerTop + CELL, color);
                if (own(level) == null && level > 0) {
                    graphics.fill(x, pickerTop + CELL - 3, x + CELL, pickerTop + CELL, 0x80000000);
                }
                String number = Integer.toString(level);
                graphics.text(font, number, x + (CELL - font.width(number)) / 2, pickerTop + 5, 0xFFFFFFFF, true);
                if (level == view.signal()) {
                    graphics.text(font, "▲", x + (CELL - font.width("▲")) / 2, pickerTop + CELL + 1, 0xFFFFFFFF);
                }
            }
        }
        CompoundTag look = own(selected);
        if (redstone() && look == null) {
            CompoundTag below = selected > 0 ? shownAt(selected - 1) : null;
            graphics.text(font, below == null ? Component.translatable("screen.reticulum.beacon_shows_nothing")
                    : Component.translatable("screen.reticulum.beacon_same_as", selected - 1), left, colorTop + 4, 0xFFA0A0A0);
            return;
        }
        if (look == null) {
            return;
        }
        graphics.text(font, Component.translatable("screen.reticulum.beacon_color"), left, colorTop + 3, 0xFFE0E0E0);
        graphics.text(font, Component.translatable("screen.reticulum.beacon_text_color_row"), left, colorTop + 21, 0xFFE0E0E0);
        int textColorNow = look.contains("text_color") ? look.getIntOr("text_color", 0) : BeaconLook.AUTO;
        DyeColor[] dyes = DyeColor.values();
        for (int row = 0; row < 2; row++) {
            int chosen = row == 0 ? look.getIntOr("color", 0) : textColorNow;
            int y = swatchY(row);
            for (int i = 0; i < dyes.length; i++) {
                int x = swatchX(i);
                int color = dyes[i].getTextureDiffuseColor() & 0xFFFFFF;
                if (color == chosen) {
                    graphics.fill(x - 1, y - 1, x + SWATCH + 1, y + SWATCH + 1, ring(color));
                }
                graphics.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | color);
            }
        }
        // "Automatic" on each row: white, struck through with a red line from the bottom left to the top right.
        for (int row = 0; row < 2; row++) {
            int ax = autoX();
            int ay = swatchY(row);
            boolean on = row == 0 ? look.getIntOr("color", 0) == defaultColor() : textColorNow == BeaconLook.AUTO;
            if (on) {
                graphics.fill(ax - 1, ay - 1, ax + SWATCH + 1, ay + SWATCH + 1, ring(0xFFFFFF));
            }
            graphics.fill(ax, ay, ax + SWATCH, ay + SWATCH, 0xFFFFFFFF);
            for (int i = 0; i < SWATCH; i++) {
                graphics.fill(ax + i, ay + SWATCH - 1 - i, ax + i + 1, ay + SWATCH - i, 0xFFE0302A);
            }
            if (mouseX >= ax && mouseX < ax + SWATCH && mouseY >= ay && mouseY < ay + SWATCH) {
                graphics.setTooltipForNextFrame(font, Component.translatable(row == 0 ? "screen.reticulum.beacon_color_auto_hint"
                        : "screen.reticulum.beacon_text_color_auto_hint"), mouseX, mouseY);
            }
        }
        if (pitch != null) {
            graphics.text(font, Component.translatable("screen.reticulum.beacon_pitch"), pitch.getX(), pitch.getY() - 10, 0xFFE0E0E0);
            graphics.text(font, Component.translatable("screen.reticulum.beacon_pitch2"), pitch2.getX(), pitch2.getY() - 10, 0xFFE0E0E0);
            graphics.text(font, Component.translatable("screen.reticulum.beacon_interval"), interval.getX(), interval.getY() - 10, 0xFFE0E0E0);
            graphics.text(font, Component.translatable("screen.reticulum.beacon_range"), range.getX(), range.getY() - 10, 0xFFE0E0E0);
        }
        if (!view.canEdit()) {
            graphics.text(font, Component.translatable("screen.reticulum.beacon_read_only"), left, pitch.getY() + 18, 0xFFA0A0A0);
        }
    }

    /** The labels of Network mode's two states. */
    private static final class StatusBeaconStatePicker {
        static final String WARNING_LABEL = "screen.reticulum.beacon_warning";
        static final String CRITICAL_LABEL = "screen.reticulum.beacon_critical";
    }
}
