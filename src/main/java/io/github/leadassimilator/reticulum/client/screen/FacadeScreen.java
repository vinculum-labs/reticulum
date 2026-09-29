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
public class FacadeScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int ROWS = 6;
    private static final int CELL = 18;
    private static final int WIDTH = COLUMNS * CELL + 16;
    private static final int HEIGHT = 200;

    /** Every item whose block can be a facade; worked out once, as it looks at every block. */
    @Nullable
    private static List<Item> allCandidates;

    private final InteractionHand hand;
    private final List<Item> candidates;
    private List<Item> shown;
    private EditBox search;
    private int scroll;
    private int left;
    private int top;

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new FacadeScreen(hand));
    }

    private FacadeScreen(InteractionHand hand) {
        super(Component.translatable("screen.reticulum.facade"));
        this.hand = hand;
        Set<Item> ordered = new LinkedHashSet<>();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            for (ItemStack stack : player.getInventory().items) {
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

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - HEIGHT) / 2);
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, left + 8, top + 42, WIDTH - 16, 16, Component.translatable("screen.reticulum.facade_search"));
        search.setHint(Component.translatable("screen.reticulum.facade_search").withStyle(s -> s.withColor(0x808080)));
        search.setValue(query);
        search.setResponder(this::filter);
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.facade_blank"), b -> choose(null))
                .bounds(left + WIDTH - 58, top + 18, 50, 18).build());
        filter(query);
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown = q.isEmpty() ? candidates : candidates.stream()
                .filter(item -> item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)
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
        PacketDistributor.sendToServer(new Payloads.PaintFacade(hand, Optional.ofNullable(block)));
        onClose();
    }

    /** Where a JEI ingredient can be dropped: the block grid and the preview. */
    public Rect2i dropArea() {
        return new Rect2i(left, top + 14, WIDTH, HEIGHT - 14);
    }

    public int guiLeft() {
        return left;
    }

    public int guiTop() {
        return top;
    }

    public static int guiWidth() {
        return WIDTH;
    }

    public static int guiHeight() {
        return HEIGHT;
    }

    private int gridTop() {
        return top + 64;
    }

    @Override
    public void tick() {
        if (!(held().getItem() instanceof FacadeItem)) {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left + 8, top + 6, 0xFFFFFF);
        // What it looks like now.
        ItemStack held = held();
        BlockState state = FacadeItem.state(held);
        graphics.fill(left + 8, top + 18, left + 26, top + 36, 0xFF202020);
        if (state != null) {
            graphics.renderItem(new ItemStack(state.getBlock()), left + 9, top + 19);
        }
        Component name = state == null ? Component.translatable("screen.reticulum.facade_none") : state.getBlock().getName();
        graphics.drawString(font, name, left + 32, top + 19, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("screen.reticulum.facade_count", held.getCount()), left + 32, top + 28, 0xA0A0A0);
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
            if (mouseX >= x && mouseX < x + CELL - 1 && mouseY >= y && mouseY < y + CELL - 1) {
                graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0x80FFFFFF);
                hovered = item;
            }
        }
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int trackHeight = ROWS * CELL - 1;
            int barHeight = Math.max(8, trackHeight * ROWS / (maxScroll + ROWS));
            int barY = y0 + (trackHeight - barHeight) * scroll / maxScroll;
            graphics.fill(left + WIDTH - 6, barY, left + WIDTH - 3, barY + barHeight, 0xFFA0A0A0);
        }
        if (shown.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.reticulum.facade_no_match"), left + WIDTH / 2, y0 + 40, 0xA0A0A0);
        }
        graphics.drawWordWrap(font, Component.translatable("screen.reticulum.facade_hint"), left + 8, y0 + ROWS * CELL + 6, WIDTH - 16, 0xA0A0A0);
        if (hovered != null) {
            graphics.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xE0101010);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF505050);
    }

    private int maxScroll() {
        int rows = (shown.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows - ROWS);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int column = (int) Math.floor((mouseX - left - 8) / CELL);
        int row = (int) Math.floor((mouseY - gridTop()) / CELL);
        if (column >= 0 && column < COLUMNS && row >= 0 && row < ROWS) {
            int index = (scroll + row) * COLUMNS + column;
            if (index < shown.size() && shown.get(index) instanceof BlockItem item) {
                choose(item.getBlock());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
