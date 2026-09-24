package com.civcraft.item.def;

import java.util.Locale;

/** What an item is for; drives default category, stack size, vanilla-use rules and combat handling. */
public enum ItemKind {
    MATERIAL("materials"),
    WEAPON("gear"),
    BOW("gear"),
    ARMOR("gear"),
    TOOL("gear"),
    CATALYST("catalysts"),
    SPECIAL("special"),
    UNIT("units"),
    ARTIFACT("artifacts"),
    SCROLL("scrolls"),
    COMPONENT("space");

    private final String defaultCategory;

    ItemKind(String defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    public String defaultCategory() {
        return defaultCategory;
    }

    /** Gear keeps its vanilla behaviour (swinging, shooting, wearing, digging). */
    public boolean isGear() {
        return this == WEAPON || this == BOW || this == ARMOR || this == TOOL;
    }

    public static ItemKind parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
