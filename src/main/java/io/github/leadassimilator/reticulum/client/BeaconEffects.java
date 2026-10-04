package io.github.leadassimilator.reticulum.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Status Beacons' glow (a pulsing bubble, through walls if set), their sound and their lens color. */
public final class BeaconEffects {
    private BeaconEffects() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null
                || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float pulse = 0.5f + 0.5f * Mth.sin((float) (System.currentTimeMillis() % 100_000) / 180f);
        // As far as the world is drawn.
        double distance = mc.options.getEffectiveRenderDistance() * 16.0;
        boolean any = false;
        for (StatusBeaconBlockEntity beacon : StatusBeaconBlockEntity.CLIENT_LOADED) {
            BeaconLook look = beacon.look();
            if (look == null || beacon.getLevel() != mc.level || beacon.isRemoved() || !look.has(BeaconLook.GLOW)) {
                continue;
            }
            BlockPos pos = beacon.getBlockPos();
            if (camera.distanceToSqr(Vec3.atCenterOf(pos)) > distance * distance) {
                continue;
            }
            // Through walls, unless it is a Redstone-mode beacon and the player hides those (against trolling).
            boolean through = look.has(BeaconLook.GLOW_THROUGH)
                    && !(beacon.mode() == StatusBeaconBlockEntity.Mode.REDSTONE && ReticulumClientConfig.HIDE_REDSTONE_BEACON_GLOW.getAsBoolean());
            int color = look.color();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            RenderType quads = through ? IdentifyRenderer.QUADS_THROUGH_WALLS : RenderType.debugQuads();
            RenderType lines = through ? IdentifyRenderer.LINES_THROUGH_WALLS : RenderType.lines();
            // Two shells breathing out of step, and an outline, so it reads as a bubble around the block.
            double outer = 0.35 + 0.35 * pulse;
            double inner = 0.15 + 0.2 * (1 - pulse);
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(outer), r, g, b, 0.10f + 0.10f * (1 - pulse));
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(inner), r, g, b, 0.18f);
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines), new AABB(pos).inflate(outer), r, g, b, 0.6f * (1 - pulse) + 0.2f);
            any = true;
        }
        if (any) {
            buffers.endBatch(IdentifyRenderer.QUADS_THROUGH_WALLS);
            buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
            buffers.endBatch(RenderType.debugQuads());
            buffers.endBatch(RenderType.lines());
        }
        pose.popPose();
    }

    /** How long each alarm's message shows before the next. */
    private static final long CYCLE_MS = 3000;

    /** Beacons' text: their look's label, or (Network mode) the alarms going off, taking turns. Through walls if set. */
    public static void drawTexts(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null
                || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        double distance = mc.options.getEffectiveRenderDistance() * 16.0;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long turn = System.currentTimeMillis() / CYCLE_MS;
        // Text hidden by walls first, then the text seen through them (drawn with depth testing off).
        for (boolean pass : new boolean[] {false, true}) {
            boolean any = false;
            for (StatusBeaconBlockEntity beacon : StatusBeaconBlockEntity.CLIENT_LOADED) {
                BeaconLook look = beacon.look();
                if (look == null || beacon.getLevel() != mc.level || beacon.isRemoved() || !look.has(BeaconLook.TEXT)
                        || camera.distanceToSqr(Vec3.atCenterOf(beacon.getBlockPos())) > distance * distance
                        || look.label().isBlank() && AlarmClient.marks(beacon.getBlockPos())) {
                    continue;
                }
                boolean through = look.has(BeaconLook.TEXT_THROUGH)
                        && !(beacon.mode() == StatusBeaconBlockEntity.Mode.REDSTONE && ReticulumClientConfig.HIDE_REDSTONE_BEACON_GLOW.getAsBoolean());
                if (through != pass) {
                    continue;
                }
                String text;
                int color;
                java.util.List<String> alarms = beacon.alarmTexts();
                if (!look.label().isBlank()) {
                    text = look.label();
                    color = look.color();
                } else if (beacon.mode() == StatusBeaconBlockEntity.Mode.NETWORK && !alarms.isEmpty()) {
                    int index = (int) (turn % alarms.size());
                    String alarm = alarms.get(index);
                    boolean critical = alarm.startsWith("!");
                    text = "⚠ " + alarm.substring(1) + (alarms.size() > 1 ? " (" + (index + 1) + "/" + alarms.size() + ")" : "");
                    color = critical ? StatusBeaconRenderer.CRITICAL_COLOR : StatusBeaconRenderer.WARNING_COLOR;
                } else {
                    continue;
                }
                WorldLabel.draw(pose, event.getCamera(), buffers, mc.font, Vec3.atCenterOf(beacon.getBlockPos()).add(0, 0.9, 0), text, color, through);
                any = true;
            }
            if (any) {
                if (pass) {
                    WorldLabel.endThroughWalls(buffers);
                } else {
                    buffers.endBatch();
                }
            }
        }
        pose.popPose();
    }

    private static void fillBox(Matrix4f m, VertexConsumer buffer, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        quad(m, buffer, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(m, buffer, r, g, b, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(m, buffer, r, g, b, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(m, buffer, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(m, buffer, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(m, buffer, r, g, b, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(Matrix4f m, VertexConsumer buffer, float r, float g, float b, float a, float... xyz) {
        for (int i = 0; i < 12; i += 3) {
            buffer.addVertex(m, xyz[i], xyz[i + 1], xyz[i + 2]).setColor(r, g, b, a);
        }
    }

    /** Each beacon's sound, every so many ticks as its look says (lower every other time when two-tone). */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        long time = mc.level.getGameTime();
        boolean muteRedstone = ReticulumClientConfig.MUTE_REDSTONE_BEACONS.getAsBoolean();
        // A network tool in hand sounding the alarm takes priority over beacons showing alarms.
        boolean muteNetwork = AlarmClient.toolSounding();
        for (StatusBeaconBlockEntity beacon : StatusBeaconBlockEntity.CLIENT_LOADED) {
            BeaconLook look = beacon.look();
            if (look == null || beacon.getLevel() != mc.level || beacon.isRemoved() || !look.has(BeaconLook.SOUND)
                    || muteRedstone && beacon.mode() == StatusBeaconBlockEntity.Mode.REDSTONE
                    || muteNetwork && beacon.mode() == StatusBeaconBlockEntity.Mode.NETWORK) {
                continue;
            }
            int interval = Math.max(1, look.interval());
            if (time % interval != 0) {
                continue;
            }
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(look.sound());
            if (sound == null) {
                continue;
            }
            float pitch = look.has(BeaconLook.TWO_TONE) && (time / interval) % 2 == 1 ? look.pitch2() : look.pitch();
            // A sound carries 16 blocks times its volume (when above 1).
            mc.level.playLocalSound(beacon.getBlockPos(), sound, SoundSource.BLOCKS, Math.max(0.2f, look.range() / 16f), pitch, false);
        }
    }

    /** The lens takes the look's color; gray when nothing is shown. */
    public static int lensColor(net.minecraft.world.level.block.state.BlockState state, @javax.annotation.Nullable net.minecraft.world.level.BlockAndTintGetter level,
            @javax.annotation.Nullable BlockPos pos, int tint) {
        if (level != null && pos != null && level.getBlockEntity(pos) instanceof StatusBeaconBlockEntity beacon) {
            BeaconLook look = beacon.look();
            return look == null ? 0x585C66 : look.color();
        }
        return 0x585C66;
    }

    public static void registerColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) {
        event.register(BeaconEffects::lensColor, io.github.leadassimilator.reticulum.registry.ModBlocks.STATUS_BEACON.get());
    }

    public static void registerItemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> 0xFF585C66, io.github.leadassimilator.reticulum.registry.ModItems.STATUS_BEACON.get());
    }
}
