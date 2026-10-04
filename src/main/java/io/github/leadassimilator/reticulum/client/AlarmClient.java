package io.github.leadassimilator.reticulum.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;


import com.mojang.blaze3d.vertex.PoseStack;

import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.item.NetworkToolItem;
import io.github.leadassimilator.reticulum.item.ToolMode;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import io.github.leadassimilator.reticulum.registry.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The network tool and alarms, on the client: its mark while something is going off, its sound from the hotbar, and
 * in Status mode markers on the blocks with something wrong — each muted per severity in the client config.
 */
public final class AlarmClient {
    /** Per network with something going off: bit 0 critical (else a warning), bit 1 not the player's (only viewable). */
    private static Map<UUID, Integer> states = Map.of();
    private static List<Payloads.StatusMarker> markers = List.of();
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

    /** The item property: 0 nothing, 0.5 a warning, 1 critical (unless the tool's mark is muted for that severity). */
    public static float toolProperty(ItemStack stack) {
        int severity = severity(stack);
        if (severity == 1 && ReticulumClientConfig.TOOL_COLOR_CRITICAL.getAsBoolean()) {
            return 1f;
        }
        if (severity >= 0 && ReticulumClientConfig.TOOL_COLOR_WARNING.getAsBoolean()) {
            return 0.5f;
        }
        return 0f;
    }

    public static void openSettings() {
        Minecraft.getInstance().setScreen(new io.github.leadassimilator.reticulum.client.screen.AlarmSettingsScreen());
    }

    // ---------------------------------------------------------------- each tick

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        long time = mc.level.getGameTime();
        // The worst any tool in the hotbar (or the off hand) cares about, for the sound.
        int worst = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof NetworkToolItem) {
                worst = Math.max(worst, severity(stack));
            }
        }
        if (player.getOffhandItem().getItem() instanceof NetworkToolItem) {
            worst = Math.max(worst, severity(player.getOffhandItem()));
        }
        if (worst == 1 && ReticulumClientConfig.TOOL_SOUND_CRITICAL.getAsBoolean()) {
            playToolSound(mc, time, ReticulumClientConfig.TOOL_SOUND_ID_CRITICAL.get(), ReticulumClientConfig.TOOL_SOUND_PITCH_CRITICAL.get(),
                    ReticulumClientConfig.TOOL_SOUND_TWO_TONE_CRITICAL.getAsBoolean(), ReticulumClientConfig.TOOL_SOUND_EVERY_CRITICAL.getAsInt(),
                    SoundEvents.NOTE_BLOCK_PLING.value());
        } else if (worst >= 0 && ReticulumClientConfig.TOOL_SOUND_WARNING.getAsBoolean()) {
            playToolSound(mc, time, ReticulumClientConfig.TOOL_SOUND_ID_WARNING.get(), ReticulumClientConfig.TOOL_SOUND_PITCH_WARNING.get(),
                    ReticulumClientConfig.TOOL_SOUND_TWO_TONE_WARNING.getAsBoolean(), ReticulumClientConfig.TOOL_SOUND_EVERY_WARNING.getAsInt(),
                    SoundEvents.NOTE_BLOCK_CHIME.value());
        }
        // Status mode: where things are wrong, asked for every two seconds while held.
        if (holdingStatusTool(player)) {
            if (time % 40 == 0) {
                PacketDistributor.sendToServer(new Payloads.RequestStatusMarkers());
            }
        } else if (!markers.isEmpty()) {
            markers = List.of();
        }
    }

    /**
     * The tool's sound, every so many seconds: once, or as a short two-tone siren (four notes over two seconds, every
     * other one lower). An unknown sound id plays {@code fallback}.
     */
    private static void playToolSound(Minecraft mc, long time, String id, double pitch, boolean twoTone, int everySeconds,
            net.minecraft.sounds.SoundEvent fallback) {
        long cycle = Math.max(twoTone ? 40 : 1, everySeconds * 20L);
        long phase = time % cycle;
        if (twoTone ? phase >= 40 || phase % 10 != 0 : phase != 0) {
            return;
        }
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        net.minecraft.sounds.SoundEvent sound = location == null ? null : net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(location);
        float at = (float) pitch * (twoTone && (phase / 10) % 2 == 1 ? 0.75f : 1f);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound != null ? sound : fallback, at, 0.6f));
    }

    /** Whether the tool in Status mode marks this block now (a beacon's own text then steps aside). */
    public static boolean marks(net.minecraft.core.BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null || markers.isEmpty() || !holdingStatusTool(player)) {
            return false;
        }
        for (Payloads.StatusMarker marker : markers) {
            if (marker.pos().equals(pos)) {
                return true;
            }
        }
        return false;
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

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || markers.isEmpty() || mc.level == null) {
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
        java.util.Map<net.minecraft.core.BlockPos, List<Payloads.StatusMarker>> byBlock = new java.util.LinkedHashMap<>();
        for (Payloads.StatusMarker marker : markers) {
            if (marker.severity() == 1 ? criticals : warnings) {
                byBlock.computeIfAbsent(marker.pos(), k -> new java.util.ArrayList<>()).add(marker);
            }
        }
        for (var entry : byBlock.entrySet()) {
            boolean critical = entry.getValue().stream().anyMatch(m -> m.severity() == 1);
            int color = critical ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(IdentifyRenderer.LINES_THROUGH_WALLS), new AABB(entry.getKey()).inflate(0.03), r, g, b, pulse);
        }
        buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
        // A warning sign and what is wrong above each block, facing the camera and growing with distance.
        long turn = System.currentTimeMillis() / CYCLE_MS;
        for (var entry : byBlock.entrySet()) {
            List<Payloads.StatusMarker> here = entry.getValue();
            Payloads.StatusMarker marker = here.get((int) (turn % here.size()));
            String count = here.size() > 1 ? " (" + (here.indexOf(marker) + 1) + "/" + here.size() + ")" : "";
            int color = marker.severity() == 1 ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
            WorldLabel.draw(pose, event.getCamera(), buffers, font, Vec3.atCenterOf(entry.getKey()).add(0, 0.9, 0), "⚠ " + marker.label() + count, color, true);
        }
        WorldLabel.endThroughWalls(buffers);
        pose.popPose();
    }
}
