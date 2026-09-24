package com.civcraft.mob;

import java.util.Locale;

/** CivCraft mob kinds (spec 04 §10, §12). Entity types and stats come from {@code balance/mobs.yml}. */
public enum MobType {
    YOBO,
    /** Small "angry" Yobo summoned when a Yobo is hit. */
    ANGRY_YOBO,
    SAVAGE,
    RUFFIAN,
    BEHEMOTH,
    /** Dungeon rat. */
    RAT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MobType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
