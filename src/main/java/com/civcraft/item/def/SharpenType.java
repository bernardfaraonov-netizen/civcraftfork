package com.civcraft.item.def;

import java.util.Locale;

/** Which catalysts an item accepts. */
public enum SharpenType {
    ATTACK, DEFENSE, NONE;

    public static SharpenType parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
