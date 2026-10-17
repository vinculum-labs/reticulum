package io.github.leadassimilator.reticulum.client.screen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.item.FacadeItem;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picks what the facades in hand look like: any block that can be a facade, owned or not, with the ones in the
 * player's inventory first. With JEI, a block can also be dragged in from its list (see the JEI plugin).
 */
public class FacadeScreen extends QuickScreen {
    private static final int COLUMNS = 9;
    private static final int ROWS = 6;
    private static final int CELL = 18;
    private static final int WIDTH = COLUMNS * CELL + 16;
    private static final int GRID_TOP = 64;
    private static final int SCROLLBAR_X = 8 + COLUMNS * CELL + 1;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int TRACK_HEIGHT = ROWS * CELL - 1;
    /** The framed preview (icon, name, count) in the top left; it is also where JEI drops go. */
    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 17;
    private static final int PREVIEW_WIDTH = WIDTH - 16 - 50 - 4;
    private static final int PREVIEW_HEIGHT = 20;

    /** Every item whose block can be a facade; worked out once, as it looks at every block. */
    @Nullable
    private static List<Item> allCandidates;

    private final InteractionHand hand;
    private final List<Item> candidates;
    private List<Item> shown;
    private EditBox search;
    private int scroll;
    private boolean draggingBar;

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new FacadeScreen(hand));
    }

    private FacadeScreen(InteractionHand hand) {
        super(Component.translatable("screen.reticulum.facade"));
        this.hand = hand;
        Set<Item> ordered = new LinkedHashSet<>();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (stack.getItem() instanceof BlockItem item && FacadeItem.canBeFacade(item.getBlock().defaultBlockState())) {
                    ordered.add(item);
                }
            }
        }
        ordered.addAll(allCandidates());
        this.candidates = List.copyOf(ordered);
        this.shown = candidates;
    }

    private static List<Item> allCandidates() {
        if (allCandidates == null) {
            List<Item> found = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof BlockItem blockItem && FacadeItem.canBeFacade(blockItem.getBlock().defaultBlockState())) {
                    found.add(item);
                }
            }
            allCandidates = List.copyOf(found);
        }
        return allCandidates;
    }

    // ---------------------------------------------------------------- layout

    /*
     * Worked out from the window rather than this screen's size: JEI asks for the layout before the screen is laid
     * out, while its size is still 0, and ignores the screen if the answer is invalid then.
     */

    public static int screenWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public static int screenHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    public static int guiWidth() {
        return WIDTH;
    }

    /** The hint under the grid; JEI is only mentioned when it is installed. */
    private static Component hint() {
        return Component.translatable(io.github.leadassimilator.reticulum.integration.Mods.JEI ? "screen.reticulum.facade_hint_jei" : "screen.reticulum.facade_hint");
    }

    /** The panel's height: the grid, then the hint wrapped to the panel's width. */
    public static int guiHeight() {
        Minecraft mc = Minecraft.getInstance();
        int hintLines = mc.font.split(hint(), WIDTH - 16).size();
        return GRID_TOP + TRACK_HEIGHT + 7 + hintLines * mc.font.lineHeight + 6;
    }

    public int guiLeft() {
        return (screenWidth() - WIDTH) / 2;
    }

    public int guiTop() {
        return Math.max(4, (screenHeight() - guiHeight()) / 2);
    }

    private int gridTop() {
        return guiTop() + GRID_TOP;
    }

    /** Where a JEI ingredient can be dropped: the framed preview. */
    public Rect2i dropArea() {
        return new Rect2i(guiLeft() + PREVIEW_X, guiTop() + PREVIEW_Y, PREVIEW_WIDTH, PREVIEW_HEIGHT);
    }

    @Override
    protected void init() {
        int left = guiLeft();
        int top = guiTop();
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, left + 8, top + 42, WIDTH - 16, 16, Component.translatable("screen.reticulum.facade_search"));
        search.setHint(Component.translatable("screen.reticulum.facade_search").withStyle(s -> s.withColor(0x808080)));
        search.setValue(query);
        search.setResponder(this::filter);
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.facade_blank"), b -> choose(null))
                .bounds(left + WIDTH - 58, top + PREVIEW_Y, 50, PREVIEW_HEIGHT).build());
        filter(query);
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown = q.isEmpty() ? candidates : candidates.stream()
                .filter(item -> item.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                        || BuiltInRegistries.ITEM.getKey(item).toString().contains(q))
                .toList();
        scroll = 0;
    }

    /** The stack being painted; closes the screen once it is gone from the hand. */
    private ItemStack held() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    /** Paints the stack in hand like {@code block} (blank when null), and closes. */
    public void choose(@Nullable Block block) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Payloads.PaintFacade(hand, Optional.ofNullable(block)));
        onClose();
    }

    @Override
    public void tick() {
        if (!(held().getItem() instanceof FacadeItem)) {
            onClose();
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(guiLeft(), guiTop(), guiLeft() + WIDTH, guiTop() + guiHeight(), 0xE0101010);
        graphics.renderOutline(guiLeft(), guiTop(), WIDTH, guiHeight(), 0xFF505050);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = guiLeft();
        int top = guiTop();
        graphics.drawString(font, title, left + 8, top + 6, 0xFFFFFFFF);
        // What it looks like now, framed; blocks dragged from JEI are dropped here.
        ItemStack held = held();
        BlockState state = FacadeItem.state(held);
        int px = left + PREVIEW_X;
        int py = top + PREVIEW_Y;
        graphics.fill(px, py, px + PREVIEW_WIDTH, py + PREVIEW_HEIGHT, 0xFF202020);
        graphics.renderOutline(px, py, PREVIEW_WIDTH, PREVIEW_HEIGHT, 0xFF707070);
        if (state != null) {
            graphics.renderItem(new ItemStack(state.getBlock()), px + 2, py + 2);
        }
        Component name = state == null ? Component.translatable("screen.reticulum.facade_none") : state.getBlock().getName();
        int textWidth = PREVIEW_WIDTH - 24;
        graphics.drawString(font, font.substrByWidth(name, textWidth).getString(), px + 21, py + 2, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.reticulum.facade_count", held.getCount()), px + 21, py + 11, 0xFFA0A0A0);
        // The blocks to pick from.
        int y0 = gridTop();
        Item hovered = null;
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            int index = scroll * COLUMNS + i;
            int x = left + 8 + (i % COLUMNS) * CELL;
            int y = y0 + (i / COLUMNS) * CELL;
            graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF303030);
            if (index >= shown.size()) {
                continue;
            }
            Item item = shown.get(index);
            graphics.renderItem(new ItemStack(item), x, y);
            if (!draggingBar && mouseX >= x && mouseX < x + CELL - 1 && mouseY >= y && mouseY < y + CELL - 1) {
                graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0x80FFFFFF);
                hovered = item;
            }
        }
        if (maxScroll() > 0) {
            int x = left + SCROLLBAR_X;
            graphics.fill(x, y0, x + SCROLLBAR_WIDTH, y0 + TRACK_HEIGHT, 0xFF202020);
            int barY = barY();
            boolean hot = draggingBar || mouseX >= x && mouseX < x + SCROLLBAR_WIDTH && mouseY >= barY && mouseY < barY + barHeight();
            graphics.fill(x, barY, x + SCROLLBAR_WIDTH, barY + barHeight(), hot ? 0xFFE0E0E0 : 0xFFA0A0A0);
        }
        if (shown.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.facade_no_match"), left + WIDTH / 2, y0 + 40, 0xFFA0A0A0);
        }
        graphics.drawWordWrap(font, hint(), left + 8, y0 + TRACK_HEIGHT + 7, WIDTH - 16, 0xFFA0A0A0);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, new ItemStack(hovered), mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- scrolling

    private int maxScroll() {
        int rows = (shown.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows - ROWS);
    }

    private int barHeight() {
        return Math.max(8, TRACK_HEIGHT * ROWS / (maxScroll() + ROWS));
    }

    private int barY() {
        int maxScroll = maxScroll();
        return gridTop() + (maxScroll == 0 ? 0 : (TRACK_HEIGHT - barHeight()) * scroll / maxScroll);
    }

    /** Scrolls so the bar's middle follows the mouse. */
    private void dragBarTo(double mouseY) {
        double travel = TRACK_HEIGHT - barHeight();
        double fraction = travel <= 0 ? 0 : (mouseY - gridTop() - barHeight() / 2.0) / travel;
        scroll = (int) Math.round(Math.max(0, Math.min(1, fraction)) * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = guiLeft();
        if (button == 0 && maxScroll() > 0 && mouseX >= left + SCROLLBAR_X && mouseX < left + SCROLLBAR_X + SCROLLBAR_WIDTH
                && mouseY >= gridTop() && mouseY < gridTop() + TRACK_HEIGHT) {
            draggingBar = true;
            dragBarTo(mouseY);
            return true;
        }
        int column = (int) Math.floor((mouseX - left - 8) / CELL);
        int row = (int) Math.floor((mouseY - gridTop()) / CELL);
        // Only a left click paints (a right or middle click shouldn't pick and close).
        if (button == 0 && column >= 0 && column < COLUMNS && row >= 0 && row < ROWS) {
            int index = (scroll + row) * COLUMNS + column;
            if (index < shown.size() && shown.get(index) instanceof BlockItem item) {
                choose(item.getBlock());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar && button == 0) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
