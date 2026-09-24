package com.civcraft.item.def;

import java.util.Locale;

/** Armor classes of spec 04 §5.1: heavy (swords, +HP, −speed), light (bows, +speed), soul and valley armor. */
public enum ArmorClass {
    HEAVY, LIGHT, SOUL, VALLEY;

    public static ArmorClass parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
