package com.civcraft.structure.type;

import java.util.Locale;

/** Territory rule of a structure (spec 02 §2 column «Приват»). */
public enum ClaimRule {
    /** Every chunk must lie inside the town's culture ("?" and "Б" in the tables). */
    CULTURE,
    /** Every chunk must be claimed by the town ("П"). */
    CLAIMED,
    /** Chunks are claimed automatically and locked (main buildings, wonders, trade buildings). */
    AUTO;

    public static ClaimRule parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
