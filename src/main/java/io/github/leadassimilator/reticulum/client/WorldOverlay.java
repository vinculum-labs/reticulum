package io.github.leadassimilator.reticulum.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;

/** Shapes drawn over the world to point at a block's face: the screens' highlight and identify markers. */
public final class WorldOverlay {
    private WorldOverlay() {
    }

    /** A flat box lying on the face, {@code margin} outside the block (so it doesn't flicker into it). */
    public static AABB faceBox(BlockPos pos, Direction side, double margin) {
        AABB box = new AABB(pos);
        return switch (side) {
            case DOWN -> new AABB(box.minX, box.minY - margin, box.minZ, box.maxX, box.minY - margin, box.maxZ);
            case UP -> new AABB(box.minX, box.maxY + margin, box.minZ, box.maxX, box.maxY + margin, box.maxZ);
            case NORTH -> new AABB(box.minX, box.minY, box.minZ - margin, box.maxX, box.maxY, box.minZ - margin);
            case SOUTH -> new AABB(box.minX, box.minY, box.maxZ + margin, box.maxX, box.maxY, box.maxZ + margin);
            case WEST -> new AABB(box.minX - margin, box.minY, box.minZ, box.minX - margin, box.maxY, box.maxZ);
            case EAST -> new AABB(box.maxX + margin, box.minY, box.minZ, box.maxX + margin, box.maxY, box.maxZ);
        };
    }

    /**
     * A box's twelve edges as lines (into a lines buffer), as wide as the game draws block outlines. Flat boxes too,
     * which a voxel shape can't hold.
     */
    public static void lineBox(PoseStack pose, VertexConsumer lines, AABB box, float r, float g, float b, float a) {
        PoseStack.Pose last = pose.last();
        int color = ARGB.colorFromFloat(a, r, g, b);
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        for (float y : new float[] {y0, y1}) {
            for (float z : new float[] {z0, z1}) {
                line(last, lines, x0, y, z, x1, y, z, color, width);
            }
        }
        for (float x : new float[] {x0, x1}) {
            for (float z : new float[] {z0, z1}) {
                line(last, lines, x, y0, z, x, y1, z, color, width);
            }
            for (float y : new float[] {y0, y1}) {
                line(last, lines, x, y, z0, x, y, z1, color, width);
            }
        }
    }

    private static void line(PoseStack.Pose pose, VertexConsumer lines, float x0, float y0, float z0, float x1, float y1, float z1, int color,
            float width) {
        Vector3f normal = new Vector3f(x1 - x0, y1 - y0, z1 - z0);
        if (normal.lengthSquared() == 0) {
            return;
        }
        normal.normalize();
        lines.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, normal).setLineWidth(width);
        lines.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }

    /** A translucent quad over the face, drawn both ways round so it shows from either side. */
    public static void fillFace(Matrix4f matrix, VertexConsumer buffer, BlockPos pos, Direction side, double margin, float r, float g, float b,
            float a) {
        AABB f = faceBox(pos, side, margin);
        float[][] corners;
        if (side.getAxis() == Direction.Axis.Y) {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.maxX, (float) f.minY, (float) f.minZ},
                    {(float) f.maxX, (float) f.minY, (float) f.maxZ}, {(float) f.minX, (float) f.minY, (float) f.maxZ}};
        } else if (side.getAxis() == Direction.Axis.Z) {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.maxX, (float) f.minY, (float) f.minZ},
                    {(float) f.maxX, (float) f.maxY, (float) f.minZ}, {(float) f.minX, (float) f.maxY, (float) f.minZ}};
        } else {
            corners = new float[][] {{(float) f.minX, (float) f.minY, (float) f.minZ}, {(float) f.minX, (float) f.minY, (float) f.maxZ},
                    {(float) f.minX, (float) f.maxY, (float) f.maxZ}, {(float) f.minX, (float) f.maxY, (float) f.minZ}};
        }
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2]).setColor(r, g, b, a);
        }
        for (int i = 3; i >= 0; i--) {
            buffer.addVertex(matrix, corners[i][0], corners[i][1], corners[i][2]).setColor(r, g, b, a);
        }
    }
}
