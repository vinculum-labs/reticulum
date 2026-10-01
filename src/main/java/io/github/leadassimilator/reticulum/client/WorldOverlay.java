package io.github.leadassimilator.reticulum.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
