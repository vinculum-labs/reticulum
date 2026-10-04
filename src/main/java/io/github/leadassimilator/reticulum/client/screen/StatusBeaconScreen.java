package io.github.leadassimilator.reticulum.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.leadassimilator.reticulum.ReticulumConfig;
import io.github.leadassimilator.reticulum.block.StatusBeaconBlock;
import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
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
    private static final int SWATCH = 12;
    private static final int RESULTS = 4;
    /** Every sound in the game, by id, for the search. */
    private static List<String> allSounds;

    private Payloads.OpenBeaconScreen view;
    private CompoundTag settings;
    /** The state being edited: 0 to 15 a signal level, or {@link StatusBeaconBlock#WARNING} / {@link StatusBeaconBlock#CRITICAL}. */
    private int selected;
    private String search = "";
    private int left;
    private int top;
    private int pickerTop;
    private int editorTop;
    private EditBox hex;
    private EditBox pitch;
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
        int alarms = redstone() ? 0 : Math.min(3, Math.max(1, view.alarms().size()));
        int height = 230 + alarms * 11;
        top = Math.max(6, (this.height - height) / 2);
        String mode = redstone() ? "redstone" : "network";
        Button modeButton = Button.builder(Component.translatable("screen.reticulum.beacon_mode_" + mode), b -> {
                    settings.putString("mode", redstone() ? "network" : "redstone");
                    send();
                })
                .bounds(left, top + 12, WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.reticulum.beacon_mode_hint_" + mode)))
                .build();
        modeButton.active = view.canEdit();
        addRenderableWidget(modeButton);
        pickerTop = top + 50 + alarms * 11;
        if (!redstone()) {
            addState(StatusBeaconStatePicker.WARNING_LABEL, StatusBeaconBlock.WARNING, left);
            addState(StatusBeaconStatePicker.CRITICAL_LABEL, StatusBeaconBlock.CRITICAL, left + 76);
        }
        editorTop = pickerTop + 28;
        int y = editorTop;
        if (redstone()) {
            boolean ownLook = own(selected) != null;
            Checkbox ownBox = Checkbox.builder(selected == 0 ? Component.translatable("screen.reticulum.beacon_own_first")
                            : Component.translatable("screen.reticulum.beacon_own_level", selected - 1), font)
                    .pos(left, y).selected(ownLook).onValueChange((box, on) -> setOwn(selected, on)).build();
            ownBox.active = view.canEdit();
            addRenderableWidget(ownBox);
            y += 24;
        }
        CompoundTag look = own(selected);
        if (look == null) {
            return;
        }
        BeaconLook current = BeaconLook.load(look, BeaconLook.WARNING);
        // Color: swatches are drawn and clicked by hand (see render and mouseClicked); the hex box is here.
        hex = kept("hex", new SeeThroughTextBox(font, left + WIDTH - 64, y, 64, 14, Component.translatable("screen.reticulum.beacon_color")));
        hex.setMaxLength(7);
        hex.setValue(String.format(Locale.ROOT, "#%06X", current.color()));
        hex.setEditable(view.canEdit());
        addRenderableWidget(hex);
        y += 22;
        y = flagRow(y, current, new int[] {BeaconLook.BEAM, BeaconLook.BEAM_THROUGH}, new String[] {"beam", "beam_through"});
        y = flagRow(y, current, new int[] {BeaconLook.GLOW, BeaconLook.GLOW_THROUGH}, new String[] {"glow", "glow_through"});
        y = flagRow(y, current, new int[] {BeaconLook.SOUND, BeaconLook.TWO_TONE}, new String[] {"sound", "two_tone"});
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.beacon_preview"), b -> preview())
                .bounds(left + WIDTH - 20, y - 22, 20, 18).build());
        // Shows the sound chosen; clicked into, all of it is selected, so typing replaces it and an arrow key keeps it
        // to edit (a long shared prefix needn't be typed again).
        String chosen = current.sound().toString();
        EditBox searchBox = kept("search", new SeeThroughTextBox(font, left, y, WIDTH, 16, Component.translatable("screen.reticulum.beacon_search")) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean wasFocused = isFocused();
                boolean used = super.mouseClicked(mouseX, mouseY, button);
                if (used && !wasFocused) {
                    moveCursorToEnd(false);
                    setHighlightPos(0);
                }
                return used;
            }
        });
        searchBox.setMaxLength(100);
        searchBox.setValue(search.isEmpty() ? chosen : search);
        searchBox.setHint(Component.translatable("screen.reticulum.beacon_search"));
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
        y += 18;
        if (!search.isBlank()) {
            for (String id : matches(search)) {
                addRenderableWidget(Button.builder(Component.literal(id), b -> {
                    change(t -> t.putString("sound", id));
                    search = "";
                    rebuildWidgets();
                }).bounds(left, y, WIDTH, 14).build());
                y += 15;
            }
        }
        y += 4;
        int third = (WIDTH - 8) / 3;
        pitch = number("pitch", left, y + 10, third, String.format(Locale.ROOT, "%.2f", current.pitch()));
        interval = number("interval", left + third + 4, y + 10, third, Integer.toString(current.interval()));
        range = number("range", left + 2 * (third + 4), y + 10, third, Integer.toString(current.range()));
    }

    private void addState(String label, int state, int x) {
        Button button = Button.builder(Component.translatable(label), b -> {
            commit();
            selected = state;
            rebuildWidgets();
        }).bounds(x, pickerTop, 72, 20).build();
        button.active = selected != state;
        addRenderableWidget(button);
    }

    private int flagRow(int y, BeaconLook look, int[] flags, String[] labels) {
        for (int i = 0; i < flags.length; i++) {
            int flag = flags[i];
            Checkbox box = Checkbox.builder(Component.translatable("screen.reticulum.beacon_" + labels[i]), font)
                    .pos(left + i * 120, y).selected(look.has(flag))
                    .onValueChange((checkbox, on) -> change(t -> t.putInt("flags", on ? t.getInt("flags") | flag : t.getInt("flags") & ~flag)))
                    .build();
            box.active = view.canEdit();
            addRenderableWidget(box);
        }
        return y + 22;
    }

    private EditBox number(String key, int x, int y, int width, String value) {
        EditBox box = kept(key, new SeeThroughTextBox(font, x, y, width, 14, Component.translatable("screen.reticulum.beacon_" + key)));
        box.setMaxLength(6);
        box.setValue(value);
        box.setEditable(view.canEdit());
        addRenderableWidget(box);
        return box;
    }

    private static List<String> matches(String text) {
        if (allSounds == null) {
            allSounds = BuiltInRegistries.SOUND_EVENT.keySet().stream().map(ResourceLocation::toString).sorted().toList();
        }
        String needle = text.toLowerCase(Locale.ROOT).trim();
        List<String> banned = bannedSounds();
        List<String> out = new ArrayList<>();
        for (String id : allSounds) {
            if (id.contains(needle) && !banned.contains(id)) {
                out.add(id);
                if (out.size() == RESULTS) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> bannedSounds() {
        try {
            return List.copyOf(ReticulumConfig.BEACON_BANNED_SOUNDS.get());
        } catch (IllegalStateException notLoaded) {
            return List.of();
        }
    }

    private void preview() {
        CompoundTag look = own(selected);
        if (look == null) {
            return;
        }
        BeaconLook current = BeaconLook.load(look, BeaconLook.WARNING);
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(current.sound());
        if (sound != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, current.pitch(), 1f));
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
        if (changed) {
            send();
        }
    }

    // ---------------------------------------------------------------- drawn by hand: the level strip and the swatches

    private int stripX(int level) {
        return left + level * (CELL + 2);
    }

    private int swatchX(int i) {
        return left + 40 + (i % 8) * (SWATCH + 2);
    }

    private int swatchY(int i) {
        return editorTop + (redstone() ? 24 : 0) - 2 + (i / 8) * (SWATCH + 2) - 6;
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
        graphics.drawString(font, view.status(), left, top + 37, 0xC0C0C0);
        if (!redstone()) {
            int y = top + 48;
            if (view.alarms().isEmpty()) {
                graphics.drawString(font, Component.translatable("screen.reticulum.beacon_quiet"), left, y, 0x80C080);
            }
            for (String alarm : view.alarms().subList(0, Math.min(3, view.alarms().size()))) {
                boolean critical = alarm.startsWith("!");
                graphics.drawString(font, font.plainSubstrByWidth(alarm.substring(1), WIDTH), left, y, critical ? 0xFF5050 : 0xFFC040);
                y += 11;
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
                    : Component.translatable("screen.reticulum.beacon_same_as", selected - 1), left, editorTop + 26, 0xA0A0A0);
            return;
        }
        if (look == null) {
            return;
        }
        int colorRow = editorTop + (redstone() ? 24 : 0);
        graphics.drawString(font, Component.translatable("screen.reticulum.beacon_color"), left, colorRow + 3, 0xE0E0E0);
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
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_interval"), interval.getX(), interval.getY() - 10, 0xE0E0E0);
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_range"), range.getX(), range.getY() - 10, 0xE0E0E0);
        }
        if (!view.canEdit()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.beacon_read_only"), left, pitch.getY() + 20, 0xA0A0A0);
        }
    }

    /** The labels of Network mode's two states. */
    private static final class StatusBeaconStatePicker {
        static final String WARNING_LABEL = "screen.reticulum.beacon_warning";
        static final String CRITICAL_LABEL = "screen.reticulum.beacon_critical";
    }
}
