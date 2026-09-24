package com.civcraft.core.util;

import java.util.HashSet;
import java.util.Set;

/** Axis-aligned, inclusive block region in a single world. */
public record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Cuboid of(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        return new Cuboid(origin.world(), origin.x(), origin.y(), origin.z(),
                origin.x() + sizeX - 1, origin.y() + sizeY - 1, origin.z() + sizeZ - 1);
    }

    public boolean contains(String w, int x, int y, int z) {
        return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.world(), pos.x(), pos.y(), pos.z());
    }

    public boolean containsColumn(String w, int x, int z) {
        return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(Cuboid other) {
        return world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public Cuboid expand(int amount) {
        return new Cuboid(world, minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public BlockPos min() {
        return new BlockPos(world, minX, minY, minZ);
    }

    public BlockPos center() {
        return new BlockPos(world, (minX + maxX) >> 1, (minY + maxY) >> 1, (minZ + maxZ) >> 1);
    }

    /** All chunks this region touches. */
    public Set<ChunkKey> chunks() {
        Set<ChunkKey> result = new HashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                result.add(new ChunkKey(world, cx, cz));
            }
        }
        return result;
    }
}
