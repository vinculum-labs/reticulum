package io.github.leadassimilator.reticulum.client;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.block.entity.BeaconLook;
import io.github.leadassimilator.reticulum.block.entity.StatusBeaconBlockEntity;
import io.github.leadassimilator.reticulum.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Status Beacons' glow (a pulsing bubble, through walls if set), their sound and their lens color. */
public final class BeaconEffects {
    private BeaconEffects() {
    }

    public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
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
            // Not through walls: hidden by them, but writing no depth, so the label inside the bubble still shows.
            RenderType quads = through ? IdentifyRenderer.QUADS_THROUGH_WALLS : IdentifyRenderer.QUADS_BEHIND_WALLS;
            RenderType lines = through ? IdentifyRenderer.LINES_THROUGH_WALLS : IdentifyRenderer.LINES_BEHIND_WALLS;
            // Two shells breathing out of step, and an outline, so it reads as a bubble around the block.
            double outer = 0.35 + 0.35 * pulse;
            double inner = 0.15 + 0.2 * (1 - pulse);
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(outer), r, g, b, 0.10f + 0.10f * (1 - pulse));
            fillBox(pose.last().pose(), buffers.getBuffer(quads), new AABB(pos).inflate(inner), r, g, b, 0.18f);
            WorldOverlay.lineBox(pose, buffers.getBuffer(lines), new AABB(pos).inflate(outer), r, g, b, 0.6f * (1 - pulse) + 0.2f);
            any = true;
        }
        if (any) {
            buffers.endBatch(IdentifyRenderer.QUADS_THROUGH_WALLS);
            buffers.endBatch(IdentifyRenderer.LINES_THROUGH_WALLS);
            buffers.endBatch(IdentifyRenderer.QUADS_BEHIND_WALLS);
            buffers.endBatch(IdentifyRenderer.LINES_BEHIND_WALLS);
        }
        pose.popPose();
    }

    /** How far a label is brought toward the camera, past the beam (its core reaches 0.2 blocks from the middle, 0.28 at the corners). */
    private static final double BEAM_CLEARANCE = 0.4;
    /** How long each alarm's message shows before the next. */
    private static final long CYCLE_MS = 3000;

    /** Beacons' text: their look's label, or (Network mode) the alarms going off, taking turns. Through walls if set. */
    public static void drawTexts(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || StatusBeaconBlockEntity.CLIENT_LOADED.isEmpty()) {
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
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
                List<String> alarms = beacon.alarmTexts();
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
                if (look.textColor() != BeaconLook.AUTO) {
                    color = look.textColor();
                }
                // Brought in front of the beam (toward the camera): drawn with walls hiding it, the beam's core, which
                // runs through the label's middle, would hide it too.
                Vec3 at = Vec3.atCenterOf(beacon.getBlockPos()).add(0, 0.9, 0);
                Vec3 toward = camera.subtract(at);
                if (toward.lengthSqr() > 1) {
                    at = at.add(toward.normalize().scale(BEAM_CLEARANCE));
                }
                WorldLabel.draw(pose, Minecraft.getInstance().gameRenderer.getMainCamera(), buffers, mc.font, at, text, color, through);
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
        // A dimension change replaces the level without unloading its chunks: its beacons go here.
        StatusBeaconBlockEntity.CLIENT_LOADED.removeIf(beacon -> beacon.getLevel() != mc.level || beacon.isRemoved());
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
            // (Frozen, /tick freeze: time stands still, so a matching tick would sound every client tick.)
            if (time % interval != 0 || !mc.level.tickRateManager().runsNormally()) {
                continue;
            }
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(look.sound());
            if (sound == null) {
                continue;
            }
            float pitch = look.has(BeaconLook.TWO_TONE) && (time / interval) % 2 == 1 ? look.pitch2() : look.pitch();
            // A sound carries 16 blocks times its volume (when above 1).
            mc.level.playLocalSound(beacon.getBlockPos(), sound, SoundSource.BLOCKS, Math.max(0.2f, look.range() / 16f), pitch, false);
        }
    }

    /** The lens (the model's tint 0) takes the look's color; gray when nothing is shown, or out of the world. */
    private static final BlockTintSource LENS = new BlockTintSource() {
        @Override
        public int color(BlockState state) {
            return 0xFF585C66;
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            if (level.getBlockEntity(pos) instanceof StatusBeaconBlockEntity beacon && beacon.look() != null) {
                return 0xFF000000 | beacon.look().color();
            }
            return color(state);
        }
    };

    public static void registerColors(RegisterColorHandlersEvent.BlockTintSources event) {
        event.register(List.of(LENS), ModBlocks.STATUS_BEACON.get());
    }
}
