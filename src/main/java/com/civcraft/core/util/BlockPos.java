package com.civcraft.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Immutable, world-qualified block position that is safe to persist and to use as a map key. */
public record BlockPos(String world, int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPos of(Location location) {
        return new BlockPos(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(world, x + dx, y + dy, z + dz);
    }

    public ChunkKey chunk() {
        return new ChunkKey(world, x >> 4, z >> 4);
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** Returns the block, or {@code null} when the world is not loaded. */
    public Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public Location location() {
        return new Location(bukkitWorld(), x, y, z);
    }

    public Location center() {
        return new Location(bukkitWorld(), x + 0.5, y + 0.5, z + 0.5);
    }

    public double distanceSquared(BlockPos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return world + ":" + x + "," + y + "," + z;
    }

    public static BlockPos parse(String value) {
        int colon = value.lastIndexOf(':');
        String[] xyz = value.substring(colon + 1).split(",");
        return new BlockPos(value.substring(0, colon), Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
    }
}
