package com.civcraft.pve;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Whether the weekly WarTime is running. The war module may install an authoritative supplier with
 * {@link #override}; until then the schedule from {@code config.yml → war.schedule} is used.
 */
public final class WarTime {

    private record Window(DayOfWeek day, LocalTime start, int minutes) {
    }

    private static final List<Window> WINDOWS = new ArrayList<>();
    private static ZoneId zone = ZoneId.of("Europe/Moscow");
    private static volatile BooleanSupplier override;

    private WarTime() {
    }

    public static void load(FileConfiguration config, ZoneId zoneId, Logger logger) {
        WINDOWS.clear();
        zone = zoneId;
        for (Map<?, ?> m : config.getMapList("war.schedule")) {
            try {
                DayOfWeek day = DayOfWeek.valueOf(String.valueOf(m.get("day")).toUpperCase(Locale.ROOT));
                LocalTime start = LocalTime.parse(String.valueOf(m.get("start")));
                Object d = m.get("duration-minutes");
                int minutes = d instanceof Number n ? n.intValue() : 120;
                WINDOWS.add(new Window(day, start, Math.max(1, minutes)));
            } catch (RuntimeException e) {
                logger.warning("Ignoring malformed war.schedule entry " + m + ": " + e.getMessage());
            }
        }
    }

    /** Lets the war module provide the real WarTime state. */
    public static void override(BooleanSupplier supplier) {
        override = supplier;
    }

    public static boolean active() {
        BooleanSupplier o = override;
        if (o != null) return o.getAsBoolean();
        ZonedDateTime now = ZonedDateTime.now(zone);
        for (Window w : WINDOWS) {
            for (int back = 0; back <= 1; back++) {
                ZonedDateTime day = now.minusDays(back);
                if (day.getDayOfWeek() != w.day()) continue;
                ZonedDateTime start = day.with(w.start());
                if (!now.isBefore(start) && now.isBefore(start.plusMinutes(w.minutes()))) return true;
            }
        }
        return false;
    }
}
