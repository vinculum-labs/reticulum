package io.github.leadassimilator.reticulum.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.client.screen.AlarmSettingsScreen;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.item.ToolMode;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The network tool and alarms, on the client: its mark while something is going off, its sound from the hotbar, and
 * in Status mode markers on the blocks with something wrong — each muted per severity in the client config.
 */
public final class AlarmClient {
    /** Per network with something going off: bit 0 critical (else a warning), bit 1 not the player's (only viewable). */
    private static Map<UUID, Integer> states = Map.of();
    private static List<Payloads.StatusMarker> markers = List.of();
    /** The level the markers are about. */
    private static ClientLevel markersLevel;
    /** How long each of a block's labels shows before the next. */
    private static final long CYCLE_MS = 3000;

    private AlarmClient() {
    }

    public static void acceptStates(Map<UUID, Integer> received) {
        states = Map.copyOf(received);
    }

    public static void acceptMarkers(List<Payloads.StatusMarker> received) {
        markers = List.copyOf(received);
    }

    public static void clear() {
        states = Map.of();
        markers = List.of();
        markersLevel = null;
    }

    /** How many networks have a warning (index 0) or a critical alarm (1) going off, of the player's own. */
    public static int[] counts() {
        int[] out = new int[2];
        states.values().stream().filter(s -> (s & 2) == 0).forEach(s -> out[s & 1]++);
        return out;
    }

    /**
     * The worst going off that a tool cares about: on the network it is linked to, else on any of the player's own.
     * -1 nothing, 0 a warning, 1 critical.
     */
    public static int severity(ItemStack tool) {
        UUID linked = tool.get(ModDataComponents.NETWORK_ID.get());
        if (linked != null) {
            Integer state = states.get(linked);
            return state == null ? -1 : state & 1;
        }
        int worst = -1;
        for (int state : states.values()) {
            if ((state & 2) == 0) {
                worst = Math.max(worst, state & 1);
            }
        }
        return worst;
    }

    /**
     * Which severities a tool cares about are going off: [0] a warning, [1] critical (on the network it is linked to,
     * else on any of the player's own; by each network's worst).
     */
    public static boolean[] going(ItemStack tool) {
        boolean[] out = new boolean[2];
        UUID linked = tool.get(ModDataComponents.NETWORK_ID.get());
        for (Map.Entry<UUID, Integer> entry : states.entrySet()) {
            int state = entry.getValue();
            if (linked != null ? linked.equals(entry.getKey()) : (state & 2) == 0) {
                out[state & 1] = true;
            }
        }
        return out;
    }

    /** The item property: 1 critical, 0.5 a warning, else 0 — each severity only where its mark isn't muted. */
    public static float toolProperty(ItemStack stack) {
        boolean[] going = going(stack);
        if (going[1] && ReticulumClientConfig.TOOL_COLOR_CRITICAL.getAsBoolean()) {
            return 1f;
        }
        if (going[0] && ReticulumClientConfig.TOOL_COLOR_WARNING.getAsBoolean()) {
            return 0.5f;
        }
        return 0f;
    }

    /** Which severity the tools in these slots sound: 1 critical, 0 a warning, -1 none (muted or nothing going off). */
    private static int sounding(List<ItemStack> tools) {
        boolean[] going = new boolean[2];
        for (ItemStack stack : tools) {
            if (stack.getItem() instanceof NetworkToolItem) {
                boolean[] these = going(stack);
                going[0] |= these[0];
                going[1] |= these[1];
            }
        }
        return going[1] && ReticulumClientConfig.TOOL_SOUND_CRITICAL.getAsBoolean() ? 1
                : going[0] && ReticulumClientConfig.TOOL_SOUND_WARNING.getAsBoolean() ? 0 : -1;
    }

    public static void openSettings() {
        Minecraft.getInstance().setScreen(new AlarmSettingsScreen());
    }

    // ---------------------------------------------------------------- each tick

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        long time = mc.level.getGameTime();
        // A level left behind (a dimension change): its markers go with it.
        if (mc.level != markersLevel) {
            markers = List.of();
            markersLevel = mc.level;
        }
        // What the tools in the hotbar (or the off hand) sound.
        int worst = sounding(hotbar(player));
        if (worst == 1) {
            playToolSound(mc, time, ReticulumClientConfig.TOOL_SOUND_ID_CRITICAL.get(), ReticulumClientConfig.TOOL_SOUND_PITCH_CRITICAL.get(),
                    ReticulumClientConfig.TOOL_SOUND_PITCH2_CRITICAL.get(),
                    ReticulumClientConfig.TOOL_SOUND_TWO_TONE_CRITICAL.getAsBoolean(), ReticulumClientConfig.TOOL_SOUND_EVERY_CRITICAL.getAsInt(),
                    SoundEvents.NOTE_BLOCK_PLING.value());
        } else if (worst == 0) {
            playToolSound(mc, time, ReticulumClientConfig.TOOL_SOUND_ID_WARNING.get(), ReticulumClientConfig.TOOL_SOUND_PITCH_WARNING.get(),
                    ReticulumClientConfig.TOOL_SOUND_PITCH2_WARNING.get(),
                    ReticulumClientConfig.TOOL_SOUND_TWO_TONE_WARNING.getAsBoolean(), ReticulumClientConfig.TOOL_SOUND_EVERY_WARNING.getAsInt(),
                    SoundEvents.NOTE_BLOCK_CHIME.value());
        }
        // Status mode: where things are wrong, asked for every two seconds while held.
        if (holdingStatusTool(player)) {
            if (time % 40 == 0 && player.level().tickRateManager().runsNormally()) {
                ClientPacketDistributor.sendToServer(new Payloads.RequestStatusMarkers());
            }
        } else if (!markers.isEmpty()) {
            markers = List.of();
        }
    }

    /**
     * The tool's sound, every so many seconds: once, or as a short two-tone siren (four notes over two seconds, every
     * other one at {@code pitch2}). An unknown sound id plays {@code fallback}.
     */
    private static void playToolSound(Minecraft mc, long time, String id, double pitch, double pitch2, boolean twoTone, int everySeconds,
            SoundEvent fallback) {
        // The game frozen (/tick freeze): its time stands still, so a matching tick would play every client tick.
        if (mc.level == null || !mc.level.tickRateManager().runsNormally()) {
            return;
        }
        long cycle = Math.max(twoTone ? 40 : 1, everySeconds * 20L);
        long phase = time % cycle;
        if (twoTone ? phase >= 40 || phase % 10 != 0 : phase != 0) {
            return;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        SoundEvent sound = location == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(location);
        float at = (float) (twoTone && (phase / 10) % 2 == 1 ? pitch2 : pitch);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound != null ? sound : fallback, at, 0.6f));
    }

    /** Whether the tool in Status mode marks this block now (a beacon's own text then steps aside). */
    public static boolean marks(BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null || markers.isEmpty() || !holdingStatusTool(player)) {
            return false;
        }
        // Only markers shown: a severity muted for markers leaves the beacon's own text be.
        boolean warnings = ReticulumClientConfig.MARKERS_WARNING.getAsBoolean();
        boolean criticals = ReticulumClientConfig.MARKERS_CRITICAL.getAsBoolean();
        for (Payloads.StatusMarker marker : markers) {
            if (marker.pos().equals(pos) && (marker.severity() == 1 ? criticals : warnings)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a network tool is sounding an alarm now, from the hotbar or the off hand (its sound on for what it cares
     * about going off): beacons showing alarms then keep quiet, the tool's sound taking priority.
     */
    public static boolean toolSounding() {
        Player player = Minecraft.getInstance().player;
        return player != null && sounding(hotbar(player)) >= 0;
    }

    /** Where a network tool sounds from: the hotbar and the off hand. */
    private static List<ItemStack> hotbar(Player player) {
        List<ItemStack> out = new ArrayList<>(10);
        for (int slot = 0; slot < 9; slot++) {
            out.add(player.getInventory().getItem(slot));
        }
        out.add(player.getOffhandItem());
        return out;
    }

    private static boolean holdingStatusTool(Player player) {
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            if (stack.getItem() instanceof NetworkToolItem && NetworkToolItem.mode(stack) == ToolMode.STATUS) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- markers

    public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (markers.isEmpty() || mc.level == null) {
            return;
        }
        boolean warnings = ReticulumClientConfig.MARKERS_WARNING.getAsBoolean();
        boolean criticals = ReticulumClientConfig.MARKERS_CRITICAL.getAsBoolean();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float pulse = 0.65f + 0.35f * Mth.sin((float) (System.currentTimeMillis() % 100_000) / 200f);
        Font font = mc.font;
        // A block can have several things wrong (a beacon carries every alarm of its network): one outline in the worst
        // one's color, and the labels taking turns every few seconds.
        Map<BlockPos, List<Payloads.StatusMarker>> byBlock = new LinkedHashMap<>();
        for (Payloads.StatusMarker marker : markers) {
            if (marker.severity() == 1 ? criticals : warnings) {
                byBlock.computeIfAbsent(marker.pos(), k -> new ArrayList<>()).add(marker);
            }
        }
        for (var entry : byBlock.entrySet()) {
            boolean critical = entry.getValue().stream().anyMatch(m -> m.severity() == 1);
            int color = critical ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            ShapeRenderer.renderLineBox(pose, buffers.getBuffer(IdentifyRenderer.LINES_THROUGH_WALLS), new AABB(entry.getKey()).inflate(0.03), r, g, b, pulse);
        }
        buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
        // A warning sign and what is wrong above each block, facing the camera and growing with distance.
        long turn = System.currentTimeMillis() / CYCLE_MS;
        for (var entry : byBlock.entrySet()) {
            List<Payloads.StatusMarker> here = entry.getValue();
            Payloads.StatusMarker marker = here.get((int) (turn % here.size()));
            String count = here.size() > 1 ? " (" + (here.indexOf(marker) + 1) + "/" + here.size() + ")" : "";
            int color = marker.severity() == 1 ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
            WorldLabel.draw(pose, event.getCamera(), buffers, font, Vec3.atCenterOf(entry.getKey()).add(0, 0.9, 0), "⚠ " + marker.label().getString() + count, color, true);
        }
        WorldLabel.endThroughWalls(buffers);
        pose.popPose();
    }
}
