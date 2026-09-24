package com.civcraft.item.def;

import java.util.Locale;

/** Where gear works: the normal worlds or the Air Valley (spec 04 §11.1). */
public enum Realm {
    NORMAL, VALLEY;

    public static Realm parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
