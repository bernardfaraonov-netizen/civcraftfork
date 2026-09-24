package com.civcraft.structure.construction;

import com.civcraft.core.util.BlockPos;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Walks every cell of a template volume bottom-up and applies an action (refresh missing blocks, turn ruins into
 * rubble...). Stops at an unloaded chunk and continues when it is loaded.
 */
public final class SweepJob implements WorldJobs.Job {

    @FunctionalInterface
    public interface CellAction {
        /** Returns true when the block was changed. */
        boolean apply(Block block, int x, int y, int z, int cell);
    }

    private final String world;
    private final BlockPos origin;
    private final int sx;
    private final int sy;
    private final int sz;
    private final CellAction action;
    private final BooleanSupplier cancelled;
    private final Runnable onDone;
    private int cursor;
    private int changed;

    public SweepJob(BlockPos origin, int sx, int sy, int sz, CellAction action, BooleanSupplier cancelled, Runnable onDone) {
        this.world = origin.world();
        this.origin = origin;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.action = action;
        this.cancelled = cancelled;
        this.onDone = onDone;
    }

    public int changed() {
        return changed;
    }

    @Override
    public int run(int budget) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            cursor = sx * sy * sz;
            return 0;
        }
        World w = Bukkit.getWorld(world);
        if (w == null) return 0;
        int total = sx * sy * sz;
        int used = 0;
        int scanned = 0;
        while (cursor < total && used < budget) {
            int x = cursor % sx;
            int z = (cursor / sx) % sz;
            int y = cursor / (sx * sz);
            int bx = origin.x() + x;
            int bz = origin.z() + z;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) break;
            int by = origin.y() + y;
            if (by >= w.getMinHeight() && by < w.getMaxHeight()) {
                if (action.apply(w.getBlockAt(bx, by, bz), x, y, z, cursor)) {
                    used++;
                    changed++;
                }
            }
            if (++scanned % 16 == 0) used++;
            cursor++;
        }
        return used;
    }

    @Override
    public boolean done() {
        return cursor >= sx * sy * sz;
    }

    @Override
    public void finished() {
        if (onDone != null && (cancelled == null || !cancelled.getAsBoolean())) onDone.run();
    }
}
