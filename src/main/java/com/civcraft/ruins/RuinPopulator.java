package com.civcraft.ruins;

import com.civcraft.core.util.BlockPos;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Places ruins while new chunks generate: the world is divided into cells of N×N chunks and each cell
 * gets at most one ruin in a chunk chosen deterministically from the world seed. Runs on world
 * generation threads, so it only touches the {@link LimitedRegion} and hands the lucky block position
 * to the main thread through a bounded queue.
 */
final class RuinPopulator extends BlockPopulator {

    private static final int MAX_PENDING = 10_000;

    private final int cellChunks;
    private final double chance;
    private final int minDistanceFromSpawn;
    private final Material lucky;
    private final double festiveChance;
    private final Queue<BlockPos> generated = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();

    RuinPopulator(int cellChunks, double chance, int minDistanceFromSpawn, Material lucky, double festiveChance) {
        this.cellChunks = Math.max(4, cellChunks);
        this.chance = chance;
        this.minDistanceFromSpawn = minDistanceFromSpawn;
        this.lucky = lucky;
        this.festiveChance = festiveChance;
    }

    /** Lucky blocks generated since the last call (main thread). */
    BlockPos poll() {
        BlockPos p = generated.poll();
        if (p != null) pending.decrementAndGet();
        return p;
    }

    @Override
    public void populate(@NotNull WorldInfo info, @NotNull Random random, int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        int cellX = Math.floorDiv(chunkX, cellChunks);
        int cellZ = Math.floorDiv(chunkZ, cellChunks);
        Random cell = new Random(info.getSeed() ^ (cellX * 341873128712L + cellZ * 132897987541L) ^ 0x5DEECE66DL);
        int ox = cell.nextInt(cellChunks);
        int oz = cell.nextInt(cellChunks);
        if (chunkX != cellX * cellChunks + ox || chunkZ != cellZ * cellChunks + oz) return;
        if (cell.nextDouble() >= chance) return;
        int bx = chunkX << 4;
        int bz = chunkZ << 4;
        if ((long) bx * bx + (long) bz * bz < (long) minDistanceFromSpawn * minDistanceFromSpawn) return;
        int size = 7 + 2 * cell.nextInt(3);
        int x0 = bx + 1 + cell.nextInt(Math.max(1, 15 - size));
        int z0 = bz + 1 + cell.nextInt(Math.max(1, 15 - size));
        RuinBuilder.Sink sink = new RuinBuilder.Sink() {
            @Override
            public Material get(int x, int y, int z) {
                return region.isInRegion(x, y, z) ? region.getType(x, y, z) : Material.AIR;
            }

            @Override
            public void set(int x, int y, int z, Material material) {
                if (region.isInRegion(x, y, z) && y > info.getMinHeight() && y < info.getMaxHeight()) {
                    region.setType(x, y, z, material);
                }
            }

            @Override
            public int surface(int x, int z) {
                return region.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            }

            @Override
            public String biome(int x, int y, int z) {
                NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                        .getKey(region.getBiome(x, y, z));
                return key == null ? "" : key.getKey();
            }
        };
        RuinBuilder.Built built = RuinBuilder.build(sink, x0, z0, size, lucky, festiveChance, cell);
        if (built == null || pending.get() >= MAX_PENDING) return;
        generated.add(new BlockPos(info.getName(), built.x(), built.y(), built.z()));
        pending.incrementAndGet();
    }
}
