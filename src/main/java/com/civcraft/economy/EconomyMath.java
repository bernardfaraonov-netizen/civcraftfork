package com.civcraft.economy;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Pure formulas of the economy spec (§6.4, §8.2, §8.3, §10, §11). No game state. */
public final class EconomyMath {

    private EconomyMath() {
    }

    /** happy% = H / (H + U) × 100; 100 % when both are zero (legacy produced NaN → Revolt, audit A-L18). */
    public static double happyPercent(double happiness, double unhappiness) {
        double h = Math.max(0, happiness);
        double u = Math.max(0, unhappiness);
        if (h + u <= 0) return 100.0;
        return Math.min(100.0, h / (h + u) * 100.0);
    }

    /** Unhappiness from players: Σ_{n=1..N} m × 2^min(n, 4) (spec §10.3). */
    public static double playerUnhappiness(int players, double m) {
        double total = 0;
        for (int n = 1; n <= players; n++) total += m * Math.pow(2, Math.min(n, 4));
        return total;
    }

    /** Distance unhappiness 0.01 × d^k, k = 0.75 when the culture touches the capital's, else 1.05. */
    public static double distanceUnhappiness(double distance, boolean touching, double factor, double kTouching, double kFar) {
        if (distance <= 0) return 0;
        return factor * Math.pow(distance, touching ? kTouching : kFar);
    }

    /** Value from a threshold table: the entry with the greatest key ≤ x (e.g. happiness states). */
    public static <V> V floorValue(NavigableMap<Double, V> table, double x) {
        Map.Entry<Double, V> e = table.floorEntry(x);
        return e == null ? table.firstEntry().getValue() : e.getValue();
    }

    /**
     * Price of the {@code n}-th claim (1-based) from a table "upper bound of range → price"
     * (spec §6.4). Beyond the last bound the last price applies.
     */
    public static long claimPrice(TreeMap<Integer, Long> table, int n) {
        Map.Entry<Integer, Long> e = table.ceilingEntry(n);
        return e != null ? e.getValue() : table.lastEntry().getValue();
    }

    /** Result of splitting an hourly town income (spec §8.2). All values in hundredths. */
    public record TaxSplit(long townShare, long civTreasury, long scienceCoins) {
    }

    /**
     * Splits an income: {@code taxes} goes to the civ, of which {@code science} is converted to
     * beakers. Rounding keeps the sum exact: townShare + civTreasury + scienceCoins == income.
     */
    public static TaxSplit splitIncome(long income, double taxes, double science) {
        if (income <= 0) return new TaxSplit(Math.max(0, income), 0, 0);
        double t = clamp01(taxes);
        double s = clamp01(science);
        long tax = Math.round(income * t);
        long sci = Math.round(tax * s);
        return new TaxSplit(income - tax, tax - sci, sci);
    }

    /** Beakers bought with {@code cents} at {@code price} coins per beaker. */
    public static double beakers(long cents, double price) {
        if (cents <= 0 || price <= 0) return 0;
        return cents / 100.0 / price;
    }

    /**
     * Daily upkeep (spec §8.3): (level + structures) × government × war × (1 + perTown × (towns − 1)).
     * The war multiplier's surcharge is reduced by the summed reductions (min 0).
     */
    public static long upkeep(long levelUpkeep, long structureUpkeep, double governmentMultiplier, double warMultiplier,
                              double warReduction, double perTownPercent, int towns) {
        double war = warMultiplier <= 1 ? 1 : 1 + (warMultiplier - 1) * Math.max(0, 1 - warReduction);
        double townFactor = 1 + perTownPercent * Math.max(0, towns - 1);
        double total = (levelUpkeep + structureUpkeep) * Math.max(0, governmentMultiplier) * war * townFactor;
        return Math.max(0, Math.round(total));
    }

    /** Farm growth tick (spec §11): floor(seeds) guaranteed plus one more with probability frac(seeds). */
    public static int growthSeeds(double growthPercent, double seedsPerTick, double random01) {
        double seeds = Math.max(0, seedsPerTick * growthPercent / 100.0);
        int whole = (int) Math.floor(seeds);
        return whole + (random01 < seeds - whole ? 1 : 0);
    }

    /** Hammers converted to beakers during /t chammers (spec §8.5). */
    public static double chammers(double hammers, double divisor) {
        return divisor <= 0 ? 0 : hammers / divisor;
    }

    /** Government transition hours (spec §13.4): players, clamped, times multipliers. */
    public static double transitionHours(int players, double min, double max, double multiplier) {
        double hours = Math.max(min, Math.min(max, players));
        return hours * Math.max(0, multiplier);
    }

    /** Revolution cost (spec §18.2). */
    public static long revolutionCost(long base, long perTown, int towns, double scoreFactor, double score, long cap) {
        double cost = base + (double) perTown * towns + scoreFactor * score;
        return Math.min(cap, Math.round(cost));
    }

    public static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }
}
