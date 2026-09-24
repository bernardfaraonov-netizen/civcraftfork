package com.civcraft.mob;

import java.util.Locale;

/** Mob tiers: Lesser (T1), Greater (T2), Elite (T3), Brutal (T4). */
public enum MobTier {
    LESSER(1), GREATER(2), ELITE(3), BRUTAL(4);

    private final int level;

    MobTier(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MobTier of(int level) {
        for (MobTier t : values()) if (t.level == level) return t;
        return null;
    }

    public static MobTier parse(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        if (v.length() == 2 && v.charAt(0) == 'T' && Character.isDigit(v.charAt(1))) return of(v.charAt(1) - '0');
        if (v.length() == 1 && Character.isDigit(v.charAt(0))) return of(v.charAt(0) - '0');
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
