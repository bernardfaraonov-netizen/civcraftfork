package com.civcraft.structure.construction;

/**
 * Construction speed formulas (spec 02 §1.4, §1.6, §1.11). Pure functions.
 * <ul>
 *   <li>time_h = hammers / (town_hammers × 2) → progress += town_hammers × 2 / 3600 per second;</li>
 *   <li>repair: town_hammers / 60 / parallel per minute = town_hammers / parallel per hour;</li>
 *   <li>architects' experience: hammers × (1 − 0.01 × floor(progress% / 2)), at most −50%.</li>
 * </ul>
 */
public final class BuildMath {

    private BuildMath() {
    }

    /** Hammers of progress per second. */
    public static double perSecond(double townHammersPerHour, double multiplier) {
        if (!Double.isFinite(townHammersPerHour) || townHammersPerHour <= 0 || multiplier <= 0) return 0;
        return townHammersPerHour * multiplier / 3600.0;
    }

    /** Seconds until {@code remaining} hammers are done, or -1 when there is no production. */
    public static long secondsRemaining(double remaining, double townHammersPerHour, double multiplier) {
        double rate = perSecond(townHammersPerHour, multiplier);
        if (remaining <= 0) return 0;
        if (rate <= 0) return -1;
        return (long) Math.ceil(remaining / rate);
    }

    /** Repair hammers per second with {@code parallel} simultaneous repairs in the town. */
    public static double repairPerSecond(double townHammersPerHour, int parallel) {
        if (!Double.isFinite(townHammersPerHour) || townHammersPerHour <= 0) return 0;
        return townHammersPerHour / Math.max(1, parallel) / 3600.0;
    }

    /** Number of template cells that should be placed at the given progress (bottom-up order). */
    public static int targetCells(int totalCells, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return (int) Math.min(totalCells, Math.floor(totalCells * p));
    }

    /** Architects' discount earned by losing a wonder race at {@code progress} (0..1). */
    public static double architectsDiscount(double progress, double per2Percent, double max) {
        double percent = Math.max(0, Math.min(1, progress)) * 100;
        return Math.max(0, Math.min(max, per2Percent * Math.floor(percent / 2)));
    }
}
