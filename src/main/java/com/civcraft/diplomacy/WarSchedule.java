package com.civcraft.diplomacy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The weekly war windows from {@code config.yml war.schedule} (day, start, duration). Pure calendar
 * logic in the configured time zone; the war module may override "is it war now" through
 * {@link WarTimeSource} (admin-started wars).
 */
public final class WarSchedule {

    public record Window(DayOfWeek day, LocalTime start, Duration length) {
    }

    private final List<Window> windows = new ArrayList<>();
    private final ZoneId zone;

    public WarSchedule(FileConfiguration config, ZoneId zone) {
        this.zone = zone;
        for (Map<?, ?> entry : config.getMapList("war.schedule")) {
            try {
                DayOfWeek day = DayOfWeek.valueOf(String.valueOf(entry.get("day")).toUpperCase(Locale.ROOT));
                LocalTime start = LocalTime.parse(String.valueOf(entry.get("start")));
                Object minutes = entry.get("duration-minutes");
                long length = minutes instanceof Number n ? n.longValue() : 120;
                windows.add(new Window(day, start, Duration.ofMinutes(Math.max(1, length))));
            } catch (RuntimeException ignored) {
                // malformed entries are skipped; an empty schedule means no scheduled wars
            }
        }
    }

    public List<Window> windows() {
        return windows;
    }

    /** Start of the window containing {@code now}, or null. */
    public Instant currentStart(Instant now) {
        ZonedDateTime t = now.atZone(zone);
        for (Window w : windows) {
            ZonedDateTime start = t.with(TemporalAdjusters.previousOrSame(w.day())).with(w.start());
            if (start.isAfter(t)) start = start.minusWeeks(1);
            Instant s = start.toInstant();
            if (!s.isAfter(now) && s.plus(w.length()).isAfter(now)) return s;
        }
        return null;
    }

    public boolean isWarTime(Instant now) {
        return currentStart(now) != null;
    }

    /** Start of the next window strictly after {@code now}; null when no windows are configured. */
    public Instant nextStart(Instant now) {
        ZonedDateTime t = now.atZone(zone);
        Instant best = null;
        for (Window w : windows) {
            ZonedDateTime start = t.with(TemporalAdjusters.nextOrSame(w.day())).with(w.start());
            if (!start.isAfter(t)) start = start.plusWeeks(1);
            Instant s = start.toInstant();
            if (best == null || s.isBefore(best)) best = s;
        }
        return best;
    }

    /** End of the window that contains {@code now}, or null. */
    public Instant currentEnd(Instant now) {
        ZonedDateTime t = now.atZone(zone);
        for (Window w : windows) {
            ZonedDateTime start = t.with(TemporalAdjusters.previousOrSame(w.day())).with(w.start());
            if (start.isAfter(t)) start = start.minusWeeks(1);
            Instant s = start.toInstant();
            if (!s.isAfter(now) && s.plus(w.length()).isAfter(now)) return s.plus(w.length());
        }
        return null;
    }
}
