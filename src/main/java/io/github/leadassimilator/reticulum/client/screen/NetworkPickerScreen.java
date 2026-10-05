package io.github.leadassimilator.reticulum.client.screen;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Lists the networks the player can bind to (for the network tool, or for one connector). Password networks ask
 * for their password first; entering it joins the network as a User.
 */
public class NetworkPickerScreen extends QuickScreen {
    private static final int WIDTH = 260;
    private static final int ROW = 22;
    private static final int VISIBLE = 8;

    private final Optional<BlockPos> connector;
    private final List<Payloads.NetworkChoice> choices;
    private int scroll;
    @Nullable
    private Payloads.NetworkChoice needsPassword;
    private EditBox password;

    public NetworkPickerScreen(Optional<BlockPos> connector, List<Payloads.NetworkChoice> choices) {
        super(Component.translatable(connector.isPresent() ? "screen.reticulum.pick_network_connector" : "screen.reticulum.pick_network_tool"));
        this.connector = connector;
        this.choices = choices;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = Math.max(8, (height - (VISIBLE * ROW + 90)) / 2);
        int y = top + 22;
        if (needsPassword != null) {
            password = new EditBox(font, left, y + 14, WIDTH, 18, Component.translatable("screen.reticulum.password"));
            password.setMaxLength(64);
            // Shown as stars, as on the controller.
            password.setFormatter((text, start) -> net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
            addRenderableWidget(password);
            setInitialFocus(password);
            addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.join"), b -> choose(needsPassword, password.getValue()))
                    .bounds(left, y + 40, WIDTH / 2 - 2, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                needsPassword = null;
                rebuildWidgets();
            }).bounds(left + WIDTH / 2 + 2, y + 40, WIDTH / 2 - 2, 20).build());
            return;
        }
        for (int i = scroll; i < Math.min(choices.size(), scroll + VISIBLE); i++) {
            Payloads.NetworkChoice choice = choices.get(i);
            Component label = Component.literal(choice.name())
                    .append(Component.literal(choice.owner().isEmpty() ? "" : "  · " + choice.owner()).withStyle(s -> s.withColor(0xA0A0A0)))
                    .append(choice.password() ? Component.literal("  🔒") : Component.empty());
            addRenderableWidget(Button.builder(label, b -> {
                if (choice.password()) {
                    needsPassword = choice;
                    rebuildWidgets();
                } else {
                    choose(choice, "");
                }
            }).bounds(left, y, WIDTH, 20).build());
            y += ROW;
        }
        int bottom = top + 22 + VISIBLE * ROW + 8;
        if (connector.isEmpty() && toolLinked()) {
            // Picking for the tool: it can also forget its network.
            addRenderableWidget(Button.builder(Component.translatable("screen.reticulum.unlink_tool"), b -> {
                PacketDistributor.sendToServer(new Payloads.UnlinkTool());
                onClose();
            }).bounds(width / 2 - 152, bottom, 150, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 2, bottom, 150, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 - 75, bottom, 150, 20).build());
        }
    }

    /** The network tool in hand is linked to a network. */
    private static boolean toolLinked() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return io.github.leadassimilator.reticulum.item.NetworkStamp.has(io.github.leadassimilator.reticulum.item.NetworkToolItem.held(player));
    }

    private void choose(Payloads.NetworkChoice choice, String secret) {
        PacketDistributor.sendToServer(new Payloads.PickNetwork(connector, choice.id(), secret));
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, choices.size() - VISIBLE);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        if (next != scroll && needsPassword == null) {
            scroll = next;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (needsPassword != null && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            choose(needsPassword, password.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Picking for a connector: closes once that connector is gone or out of reach. */
    @Override
    protected BlockPos block() {
        return connector.orElse(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = Math.max(8, (height - (VISIBLE * ROW + 90)) / 2);
        graphics.drawString(font, title, left, top, 0xFFFFFF);
        if (needsPassword != null) {
            graphics.drawString(font, Component.translatable("screen.reticulum.password_for", needsPassword.name()), left, top + 22, 0xE0E0E0);
        } else if (choices.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.reticulum.no_networks"), left, top + 26, 0xA0A0A0);
        } else if (choices.size() > VISIBLE) {
            Component more = Component.literal((scroll + 1) + "–" + Math.min(choices.size(), scroll + VISIBLE) + " / " + choices.size());
            graphics.drawString(font, more, left + WIDTH - font.width(more), top, 0xA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
