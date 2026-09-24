package com.civcraft.structure.construction;

import com.civcraft.core.task.Tasks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Budgeted world edits (terrain restoration, rubble, refresh sweeps). Each server tick the jobs share a block budget
 * round-robin. Jobs only touch loaded chunks; work in unloaded chunks waits until a player loads them, so timers never
 * force-load chunks.
 */
public final class WorldJobs {

    /** A unit of world work. */
    public interface Job {

        /** Performs at most {@code budget} block changes; returns how many were used. Main thread. */
        int run(int budget);

        boolean done();

        /** Called once on the main thread when {@link #done()} became true. */
        default void finished() {
        }
    }

    private final Logger logger;
    private final List<Job> jobs = new ArrayList<>();
    private int next;

    public WorldJobs(Logger logger) {
        this.logger = logger;
    }

    public void add(Job job) {
        Tasks.checkMain();
        jobs.add(job);
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    public int size() {
        return jobs.size();
    }

    /** Runs jobs with the given total budget; returns the unused budget. */
    public int tick(int budget) {
        if (jobs.isEmpty() || budget <= 0) return budget;
        int n = jobs.size();
        int share = Math.max(16, budget / n);
        int start = next % n;
        next = start + 1;
        for (int i = 0; i < n && budget > 0; i++) {
            Job job = jobs.get((start + i) % n);
            if (job.done()) continue;
            try {
                budget -= job.run(Math.min(share, budget));
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "World job failed; dropping it", e);
                jobs.remove(job);
                return budget;
            }
        }
        for (Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.done()) {
                it.remove();
                try {
                    job.finished();
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "World job completion failed", e);
                }
            }
        }
        return budget;
    }
}
