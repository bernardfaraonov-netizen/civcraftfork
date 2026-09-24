package com.civcraft.core.util;

import java.time.Duration;

public final class Durations {

    private Durations() {
    }

    /** Human readable Russian duration: "1 д 2 ч 5 мин". */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" д ");
        if (hours > 0) sb.append(hours).append(" ч ");
        if (minutes > 0) sb.append(minutes).append(" мин ");
        if (days == 0 && hours == 0 && (secs > 0 || minutes == 0)) sb.append(secs).append(" сек ");
        return sb.toString().trim();
    }

    public static String formatMillis(long millis) {
        return format(Duration.ofMillis(millis));
    }

    /** Parses "1d2h30m10s"; returns null when malformed. */
    public static Duration parse(String input) {
        long total = 0;
        long number = -1;
        for (char c : input.toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) {
                number = (number < 0 ? 0 : number * 10) + (c - '0');
                continue;
            }
            if (number < 0) return null;
            switch (c) {
                case 'd' -> total += number * 86400;
                case 'h' -> total += number * 3600;
                case 'm' -> total += number * 60;
                case 's' -> total += number;
                default -> {
                    return null;
                }
            }
            number = -1;
        }
        if (number >= 0) total += number;
        return Duration.ofSeconds(total);
    }
}
