package com.civcraft.item.gear;

/**
 * Pure combat formulas.
 *
 * <p>CivCraft uses the 1.8 armor rule (4% per armor point, at most 80%, no toughness; spec 04 §5.3).
 * The server still applies its own 1.21 armor formula to the event's base damage, so the listener feeds
 * it the base damage that the vanilla formula turns into the desired result ({@link #baseForVanilla}).
 */
public final class CombatMath {

    private CombatMath() {
    }

    /** 1.8 armor: fraction of damage absorbed. */
    public static double reduction(double armor, double perPoint, double max) {
        if (!Double.isFinite(armor) || armor <= 0) return 0;
        return Math.min(max, armor * perPoint);
    }

    /** Damage after the 1.8 armor rule. */
    public static double afterArmor(double damage, double armor, double perPoint, double max) {
        return damage * (1 - reduction(armor, perPoint, max));
    }

    /** Minecraft 1.21 {@code CombatRules.getDamageAfterAbsorb} without enchantment effects. */
    public static double vanillaAfterArmor(double damage, double armor, double toughness) {
        double f = 2.0 + toughness / 4.0;
        double effective = clamp(armor - damage / f, armor * 0.2, 20.0);
        return damage * (1.0 - effective / 25.0);
    }

    /**
     * The base damage {@code d} for which the vanilla armor formula yields {@code target}. The vanilla
     * function is monotonic in {@code d} and never absorbs more than 80%, so {@code d ∈ [target, 5·target]}.
     */
    public static double baseForVanilla(double target, double armor, double toughness) {
        if (!(target > 0)) return 0;
        if (armor <= 0) return target;
        double lo = target;
        double hi = target * 5.0 + 1.0;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) / 2;
            if (vanillaAfterArmor(mid, armor, toughness) < target) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** Arrow damage from the bow's full-draw damage and the draw force (0..1). */
    public static double arrowDamage(double fullDraw, double force) {
        double f = Double.isFinite(force) ? Math.max(0, Math.min(1, force)) : 0;
        return fullDraw * f;
    }

    /**
     * Attack sharpening that actually counts.
     *
     * @param level       sharpening level on the weapon
     * @param highTech    the owner's civ researched the War Lab tech (otherwise capped at {@code unlockedCap})
     * @param unlockedCap cap without that tech (+2)
     * @param otherCap    further cap from armor rules (sword without full T4/soul set +3, soul wearer +6/+5)
     */
    public static int effectiveSharpen(int level, boolean highTech, int unlockedCap, int otherCap) {
        int effective = Math.max(0, level);
        if (!highTech) effective = Math.min(effective, unlockedCap);
        return Math.min(effective, Math.max(0, otherCap));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
