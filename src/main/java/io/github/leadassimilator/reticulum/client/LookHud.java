package io.github.leadassimilator.reticulum.client;

import java.util.List;

import javax.annotation.Nullable;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.integration.Mods;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Built-in look-at info: while holding the network tool, a few lines under the crosshair describe the network block
 * or device being looked at, whatever the block. With The One Probe it stays off (the probe shows the lines for every
 * block). Jade shows them only while the tool isn't held (and only for blocks with a block entity), so with the tool
 * they are always here, never in two places.
 */
public final class LookHud {
    private static final int REFRESH_TICKS = 10;

    @Nullable
    private static BlockPos requestedPos;
    @Nullable
    private static Direction requestedFace;
    private static int ticksSinceRequest;
    @Nullable
    private static BlockPos shownPos;
    private static List<Component> lines = List.of();
    private static Boolean enabled;

    private LookHud() {
    }

    private static boolean enabled() {
        if (enabled == null) {
            enabled = !Mods.TOP;
        }
        return enabled;
    }

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Identifier.fromNamespaceAndPath(Reticulum.MODID, "look_at"), LookHud::render);
    }

    public static void accept(BlockPos pos, List<Component> newLines) {
        shownPos = pos;
        lines = List.copyOf(newLines);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult target = target(mc);
        if (target == null) {
            requestedPos = null;
            shownPos = null;
            return;
        }
        ticksSinceRequest++;
        if (!target.getBlockPos().equals(requestedPos) || target.getDirection() != requestedFace || ticksSinceRequest >= REFRESH_TICKS) {
            requestedPos = target.getBlockPos();
            requestedFace = target.getDirection();
            ticksSinceRequest = 0;
            ClientPacketDistributor.sendToServer(new Payloads.LookRequest(requestedPos, requestedFace));
        }
    }

    @Nullable
    private static BlockHitResult target(Minecraft mc) {
        if (!enabled() || mc.player == null || mc.level == null || NetworkToolItem.held(mc.player).isEmpty()) {
            return null;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult target = target(mc);
        if (target == null || !target.getBlockPos().equals(shownPos) || lines.isEmpty() || mc.options.hideGui) {
            return;
        }
        int y = graphics.guiHeight() / 2 + 12;
        int centerX = graphics.guiWidth() / 2;
        for (Component line : lines) {
            graphics.drawString(mc.font, line, centerX - mc.font.width(line) / 2, y, 0xFFFFFFFF);
            y += mc.font.lineHeight + 1;
        }
    }
}
