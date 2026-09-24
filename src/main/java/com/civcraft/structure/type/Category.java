package com.civcraft.structure.type;

import java.util.Locale;

/** Structure classes of spec 02 §1.1 as used by the balance files. */
public enum Category {
    BUILDING,
    IMPROVEMENT,
    WONDER,
    NATIONAL_WONDER,
    MAIN,
    WALL,
    ROAD,
    DEFENSE,
    SHIP,
    SPECIAL;

    public boolean isWonder() {
        return this == WONDER || this == NATIONAL_WONDER;
    }

    /** Built with a custom flow (two markers, item...) instead of a template placed with /build. */
    public boolean isCustom() {
        return this == WALL || this == ROAD;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Category parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
