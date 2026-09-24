package com.civcraft.core.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * Immutable, world-qualified chunk coordinate. Used as a map key for claims so lookups never
 * touch (or load) the actual chunk.
 */
public record ChunkKey(String world, int x, int z) {

    public static ChunkKey of(Location location) {
        return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ChunkKey of(Block block) {
        return new ChunkKey(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
    }

    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public ChunkKey offset(int dx, int dz) {
        return new ChunkKey(world, x + dx, z + dz);
    }

    /** Chebyshev distance in chunks; only meaningful within one world. */
    public int distance(ChunkKey other) {
        return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
    }

    public double euclidean(ChunkKey other) {
        long dx = x - other.x;
        long dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return world + ":" + x + "," + z;
    }

    public static ChunkKey parse(String value) {
        int colon = value.lastIndexOf(':');
        String[] xz = value.substring(colon + 1).split(",");
        return new ChunkKey(value.substring(0, colon), Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
    }
}
