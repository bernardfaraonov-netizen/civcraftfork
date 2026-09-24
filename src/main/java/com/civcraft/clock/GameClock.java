package com.civcraft.clock;

import com.civcraft.core.task.Tasks;
import com.civcraft.storage.DocumentStore;
import com.civcraft.storage.SaveQueue;
import com.civcraft.storage.Stored;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives the game's periodic processes. Hourly jobs fire at the top of every hour, daily jobs at the
 * configured hour. Last execution times are persisted, so a daily tick missed while the server was
 * down runs once on startup (the legacy DailyEvent ran only once per JVM lifetime).
 */
public final class GameClock {

    public static final String COLLECTION = "clock";

    /** Standard ordering for hourly/daily jobs; lower runs first. */
    public static final int PRODUCTION = 100;
    public static final int INCOME = 200;
    public static final int TAXES = 300;
    public static final int UPKEEP = 400;
    public static final int CONSEQUENCES = 500;
    public static final int CLEANUP = 900;

    private record Job(int order, String name, Runnable action) {
    }

    private static final class State implements Stored {
        private Instant lastHourly;
        private Instant lastDaily;

        @Override
        public String storageId() {
            return "state";
        }
    }

    private final Tasks tasks;
    private final Logger logger;
    private final ZoneId zone;
    private final int dailyHour;
    private final SaveQueue saves;
    private final List<Job> hourly = new ArrayList<>();
    private final List<Job> daily = new ArrayList<>();
    private final List<Job> minute = new ArrayList<>();
    private final List<Job> second = new ArrayList<>();
    private State state = new State();

    public GameClock(Tasks tasks, Logger logger, ZoneId zone, int dailyHour, SaveQueue saves) {
        this.tasks = tasks;
        this.logger = logger;
        this.zone = zone;
        this.dailyHour = dailyHour;
        this.saves = saves;
    }

    public void load(DocumentStore store) {
        store.createCollection(COLLECTION);
        List<State> loaded = store.loadAll(COLLECTION, State.class);
        if (!loaded.isEmpty()) state = loaded.getFirst();
    }

    public ZoneId zone() {
        return zone;
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(zone);
    }

    public void hourly(int order, String name, Runnable action) {
        hourly.add(new Job(order, name, action));
        hourly.sort(Comparator.comparingInt(Job::order));
    }

    public void daily(int order, String name, Runnable action) {
        daily.add(new Job(order, name, action));
        daily.sort(Comparator.comparingInt(Job::order));
    }

    public void everyMinute(String name, Runnable action) {
        minute.add(new Job(0, name, action));
    }

    public void everySecond(String name, Runnable action) {
        second.add(new Job(0, name, action));
    }

    public void start() {
        Instant now = Instant.now();
        if (state.lastHourly == null) state.lastHourly = truncateHour(now);
        if (state.lastDaily == null) state.lastDaily = lastDailyBoundary(now);
        // Catch up a missed daily tick (at most once).
        if (lastDailyBoundary(now).isAfter(state.lastDaily)) {
            tasks.later(20 * 30, this::runDaily);
        }
        tasks.timer(20, 20, this::tickSecond);
    }

    private int secondCounter;

    private void tickSecond() {
        run(second);
        if (++secondCounter % 60 == 0) run(minute);
        Instant now = Instant.now();
        if (truncateHour(now).isAfter(state.lastHourly)) {
            state.lastHourly = truncateHour(now);
            saves.save(COLLECTION, state);
            run(hourly);
        }
        if (lastDailyBoundary(now).isAfter(state.lastDaily)) {
            runDaily();
        }
    }

    private void runDaily() {
        state.lastDaily = lastDailyBoundary(Instant.now());
        saves.save(COLLECTION, state);
        logger.info("Running daily upkeep");
        run(daily);
    }

    /** Runs the hourly jobs immediately (admin command / tests). */
    public void forceHourly() {
        run(hourly);
    }

    public void forceDaily() {
        run(daily);
    }

    private void run(List<Job> jobs) {
        for (Job job : jobs) {
            try {
                job.action().run();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Clock job '" + job.name() + "' failed", e);
            }
        }
    }

    private Instant truncateHour(Instant instant) {
        return ZonedDateTime.ofInstant(instant, zone).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    private Instant lastDailyBoundary(Instant instant) {
        ZonedDateTime now = ZonedDateTime.ofInstant(instant, zone);
        ZonedDateTime boundary = now.withHour(dailyHour).withMinute(0).withSecond(0).withNano(0);
        if (boundary.isAfter(now)) boundary = boundary.minusDays(1);
        return boundary.toInstant();
    }

    public Duration untilNextDaily() {
        return Duration.between(Instant.now(), lastDailyBoundary(Instant.now()).plus(Duration.ofDays(1)));
    }
}
