package com.civcraft.science;

import com.civcraft.CivCraft;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * The weekly war window from {@code config.yml → war.schedule}. Religion ratings are frozen during war
 * and religion loss conditions are evaluated when it ends (spec 01 §16.2–16.3).
 */
public final class WarWindow {

    private record Slot(DayOfWeek day, LocalTime start, Duration duration) {
    }

    private final CivCraft civ;
    private final List<Slot> slots = new ArrayList<>();

    public WarWindow(CivCraft civ) {
        this.civ = civ;
        for (Map<?, ?> entry : civ.plugin().getConfig().getMapList("war.schedule")) {
            try {
                DayOfWeek day = DayOfWeek.valueOf(String.valueOf(entry.get("day")).toUpperCase(Locale.ROOT));
                LocalTime start = LocalTime.parse(String.valueOf(entry.get("start")));
                Object minutes = entry.get("duration-minutes");
                long m = minutes instanceof Number n ? n.longValue() : 120;
                slots.add(new Slot(day, start, Duration.ofMinutes(Math.max(1, m))));
            } catch (RuntimeException e) {
                civ.logger().log(Level.WARNING, "Invalid war.schedule entry " + entry, e);
            }
        }
    }

    public boolean isWarTime() {
        ZonedDateTime now = civ.clock().now();
        for (Slot slot : slots) {
            for (int back = 0; back <= 7; back++) {
                ZonedDateTime day = now.minusDays(back);
                if (day.getDayOfWeek() != slot.day()) continue;
                ZonedDateTime start = day.with(slot.start());
                if (!now.isBefore(start) && now.isBefore(start.plus(slot.duration()))) return true;
            }
        }
        return false;
    }
}
