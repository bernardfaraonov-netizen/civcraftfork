package com.civcraft.item.enhance;

import com.civcraft.item.def.SharpenType;

/**
 * Sharpening chance formula (spec 04 §6.3):
 * <pre>
 * chance = base + bonus + personal − floor(level / step) × penalty
 * base: attack 40, defense 50; mystic catalysts always 100
 * chance ≤ 0 → min-chance ("one in a million"); chance ≤ 100
 * </pre>
 * After a failure the player's hidden personal bonus grows by {@code tier × perTier} (max {@code personalMax})
 * and it resets to 0 after a success.
 */
public final class SharpenMath {

    public record Config(double baseAttack, double baseDefense, int stepSize, double stepPenalty, double minChance,
                         double personalPerTier, double personalMax) {

        public Config {
            if (stepSize < 1) throw new IllegalArgumentException("step size must be >= 1");
        }
    }

    private SharpenMath() {
    }

    /**
     * @param level    current sharpening level of the item
     * @param personal hidden personal bonus of the player, percentage points
     * @param bonus    other bonuses (titanium trade resource...), percentage points
     * @return success chance in percent, in (0, 100]
     */
    public static double chance(Config c, SharpenType type, boolean mystic, int level, double personal, double bonus) {
        if (mystic) return 100.0;
        double base = type == SharpenType.DEFENSE ? c.baseDefense() : c.baseAttack();
        double steps = Math.floor(Math.max(0, level) / (double) c.stepSize());
        double chance = base + sanitize(bonus) + sanitize(personal) - steps * c.stepPenalty();
        if (chance <= 0) return c.minChance();
        return Math.min(100.0, chance);
    }

    /** Whether a roll in [0, 1) succeeds for a chance in percent. */
    public static boolean succeeds(double chancePercent, double roll) {
        return roll * 100.0 < chancePercent;
    }

    /** Personal bonus after an attempt with a catalyst of the given tier. */
    public static double nextPersonal(Config c, double personal, int catalystTier, boolean success) {
        if (success) return 0;
        return Math.min(c.personalMax(), sanitize(personal) + Math.max(0, catalystTier) * c.personalPerTier());
    }

    private static double sanitize(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
