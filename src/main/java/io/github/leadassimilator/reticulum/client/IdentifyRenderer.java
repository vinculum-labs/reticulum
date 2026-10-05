package io.github.leadassimilator.reticulum.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import io.github.leadassimilator.reticulum.Reticulum;
import io.github.leadassimilator.reticulum.ReticulumClientConfig;
import io.github.leadassimilator.reticulum.protocol.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Draws what is identified: a pulsing outline (or a filled face) seen through walls, the name above it, and past a
 * distance a beam up to the sky; plus a short list on screen with distance and direction.
 */
public final class IdentifyRenderer {
    /** How far outside the block a face is drawn. */
    private static final double MARGIN = 0.01;
    /** Lines that ignore depth, so they show through walls. */
    static final RenderType LINES_THROUGH_WALLS = RenderType.create("reticulum_identify_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(States.NO_DEPTH)
                    .createCompositeState(false));
    /** Translucent quads that ignore depth, for a highlighted face. */
    static final RenderType QUADS_THROUGH_WALLS = RenderType.create("reticulum_identify_quads", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(States.NO_DEPTH)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * Translucent quads that walls hide but that hide nothing themselves (no depth written): a glow around a block, so
     * text inside it (a beacon's label) still shows.
     */
    static final RenderType QUADS_BEHIND_WALLS = RenderType.create("reticulum_glow_quads", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    /** Lines walls hide that hide nothing themselves, likewise. */
    static final RenderType LINES_BEHIND_WALLS = RenderType.create("reticulum_glow_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Only subclasses may name some render state classes. Never instantiated. */
    private abstract static class States extends RenderType {
        /**
         * Depth testing really off. Vanilla's NO_DEPTH_TEST leaves whatever state came before it, which during level
         * rendering is usually on, so lines under a surface (a box's bottom edges on the ground) were hidden.
         */
        static final DepthTestStateShard NO_DEPTH = new DepthTestStateShard("reticulum_no_depth", GL11.GL_ALWAYS) {
            private boolean wasEnabled;

            @Override
            public void setupRenderState() {
                wasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                RenderSystem.disableDepthTest();
            }

            @Override
            public void clearRenderState() {
                if (wasEnabled) {
                    RenderSystem.enableDepthTest();
                }
            }
        };

        private States() {
            super("", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 0, false, false, () -> {
            }, () -> {
            });
        }
    }

    private static List<Payloads.IdentifyMarker> markers = List.of();

    private IdentifyRenderer() {
    }

    public static void accept(List<Payloads.IdentifyMarker> received) {
        markers = List.copyOf(received);
    }

    // ---------------------------------------------------------------- in the world

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || markers.isEmpty() || mc.level == null) {
            return;
        }
        boolean throughWalls = ReticulumClientConfig.IDENTIFY_THROUGH_WALLS.getAsBoolean();
        int beamDistance = ReticulumClientConfig.IDENTIFY_BEAM_DISTANCE.getAsInt();
        // Not through walls: hidden by them, but writing no depth, so the column of light (which runs through the
        // label) and the boxes never hide a label's letters.
        RenderType lines = throughWalls ? LINES_THROUGH_WALLS : LINES_BEHIND_WALLS;
        RenderType quads = throughWalls ? QUADS_THROUGH_WALLS : QUADS_BEHIND_WALLS;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float pulse = 0.6f + 0.4f * Mth.sin((float) (System.currentTimeMillis() % 100_000) / 200f);
        int top = mc.level.getMaxBuildHeight();
        for (Payloads.IdentifyMarker marker : markers) {
            if (!marker.pos().dimension().equals(mc.level.dimension())) {
                continue;
            }
            BlockPos pos = marker.pos().pos();
            float r = ((marker.color() >> 16) & 0xFF) / 255f;
            float g = ((marker.color() >> 8) & 0xFF) / 255f;
            float b = (marker.color() & 0xFF) / 255f;
            if (marker.face().isPresent()) {
                Direction face = marker.face().get();
                WorldOverlay.fillFace(pose.last().pose(), buffers.getBuffer(quads), pos, face, MARGIN, r, g, b, 0.35f * pulse);
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines), WorldOverlay.faceBox(pos, face, MARGIN), r, g, b, pulse);
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines), new AABB(pos).inflate(0.01), r, g, b, 0.35f * pulse);
            } else {
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines), new AABB(pos).inflate(0.02), r, g, b, pulse);
            }
            double distance = Math.sqrt(camera.distanceToSqr(Vec3.atCenterOf(pos)));
            if (distance > beamDistance && top > pos.getY() + 1) {
                // A thin column of light from the block up to the sky.
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(lines),
                        new AABB(pos.getX() + 0.45, pos.getY() + 1, pos.getZ() + 0.45, pos.getX() + 0.55, top, pos.getZ() + 0.55), r, g, b, 0.8f);
            }
        }
        buffers.endBatch(quads);
        buffers.endBatch(lines);
        // Names above the blocks, facing the camera and growing with distance so they stay readable.
        Font font = mc.font;
        for (Payloads.IdentifyMarker marker : markers) {
            if (!marker.pos().dimension().equals(mc.level.dimension())) {
                continue;
            }
            WorldLabel.draw(pose, event.getCamera(), buffers, font, Vec3.atCenterOf(marker.pos().pos()).add(0, 0.9, 0), marker.label(),
                    marker.color(), throughWalls);
        }
        if (throughWalls) {
            WorldLabel.endThroughWalls(buffers);
        } else {
            buffers.endBatch();
        }
        pose.popPose();
    }

    // ---------------------------------------------------------------- on screen

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath(Reticulum.MODID, "identify"), IdentifyRenderer::renderHud);
    }

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int HUD_LINES = 6;

    /** Up to six lit things, nearest first, with how far and which way; those in other dimensions say so. */
    private static void renderHud(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (markers.isEmpty() || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        record Entry(Payloads.IdentifyMarker marker, double distance) {
        }
        List<Entry> entries = new ArrayList<>();
        for (Payloads.IdentifyMarker marker : markers) {
            boolean here = marker.pos().dimension().equals(mc.player.level().dimension());
            entries.add(new Entry(marker, here ? Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(marker.pos().pos()))) : Double.MAX_VALUE));
        }
        entries.sort(Comparator.comparingDouble(Entry::distance));
        int y = 4;
        for (int i = 0; i < Math.min(HUD_LINES, entries.size()); i++) {
            Entry entry = entries.get(i);
            String where;
            if (entry.distance() == Double.MAX_VALUE) {
                where = entry.marker().pos().dimension().location().getPath().replace('_', ' ');
            } else {
                Vec3 to = Vec3.atCenterOf(entry.marker().pos().pos()).subtract(eye);
                // Minecraft yaw: 0 is south, 90 west; turning right increases it.
                float targetYaw = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
                float relative = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
                String arrow = ARROWS[Math.floorMod(Math.round(relative / 45f), 8)];
                String vertical = to.y > 3 ? " ▲" : to.y < -3 ? " ▼" : "";
                where = Math.round(entry.distance()) + " m " + arrow + vertical;
            }
            graphics.fill(2, y - 1, 8, y + 7, 0xFF000000 | entry.marker().color());
            graphics.drawString(mc.font, entry.marker().label() + "  " + where, 11, y, 0xFFFFFF, true);
            y += 10;
        }
        if (entries.size() > HUD_LINES) {
            graphics.drawString(mc.font, "+" + (entries.size() - HUD_LINES) + " more", 11, y, 0xA0A0A0, true);
        }
    }
}
