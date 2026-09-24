package com.civcraft.structure.construction;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/** A set of block changes grouped by chunk; each chunk is processed only while it is loaded. */
public final class ChangeJob implements WorldJobs.Job {

    private record Change(int x, int y, int z, BlockData data) {
    }

    private final String world;
    private final Map<Long, ArrayDeque<Change>> pending = new LinkedHashMap<>();
    private final Runnable onDone;
    private int remaining;

    public ChangeJob(String world, Runnable onDone) {
        this.world = world;
        this.onDone = onDone;
    }

    public void add(int x, int y, int z, BlockData data) {
        long key = (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
        pending.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Change(x, y, z, data));
        remaining++;
    }

    public int remaining() {
        return remaining;
    }

    @Override
    public int run(int budget) {
        World w = Bukkit.getWorld(world);
        if (w == null) return 0;
        int used = 0;
        for (Iterator<Map.Entry<Long, ArrayDeque<Change>>> it = pending.entrySet().iterator(); it.hasNext() && used < budget; ) {
            Map.Entry<Long, ArrayDeque<Change>> e = it.next();
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            if (!w.isChunkLoaded(cx, cz)) continue;
            ArrayDeque<Change> queue = e.getValue();
            while (!queue.isEmpty() && used < budget) {
                Change c = queue.poll();
                Block block = w.getBlockAt(c.x(), c.y(), c.z());
                if (!block.getBlockData().equals(c.data())) {
                    BlockWork.dropContents(block);
                    block.setBlockData(c.data(), false);
                }
                used++;
                remaining--;
            }
            if (queue.isEmpty()) it.remove();
        }
        return used;
    }

    @Override
    public boolean done() {
        return pending.isEmpty();
    }

    @Override
    public void finished() {
        if (onDone != null) onDone.run();
    }
}
