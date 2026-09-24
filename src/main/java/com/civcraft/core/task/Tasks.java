package com.civcraft.core.task;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Thin, explicit wrapper around the Bukkit scheduler. All world and game-state mutation happens on
 * the main thread; only IO (database, HTTP) runs async. The legacy plugin mixed both freely, which
 * caused the majority of its race conditions and "async chunk access" crashes.
 */
public final class Tasks {

    private final Plugin plugin;
    private final Executor mainExecutor;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
        this.mainExecutor = this::sync;
    }

    public static boolean isMain() {
        return Bukkit.isPrimaryThread();
    }

    public static void checkMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Must be called on the main server thread");
        }
    }

    /** Executor that runs on the main thread (immediately when already there). */
    public Executor main() {
        return mainExecutor;
    }

    public void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public BukkitTask nextTick(Runnable task) {
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    public BukkitTask later(long delayTicks, Runnable task) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public BukkitTask timer(long delayTicks, long periodTicks, Runnable task) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public BukkitTask async(Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public BukkitTask asyncTimer(long delayTicks, long periodTicks, Runnable task) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }

    /** Runs {@code supplier} on the main thread and completes the future with its result. */
    public <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, mainExecutor);
    }
}
