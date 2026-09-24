package com.civcraft.structure.type;

import java.util.Locale;

/**
 * A structure that must exist before another can be built ("Нужно здание"). {@code level} &gt; 0 also requires that
 * level (World Bank needs a level 10 bank).
 */
public record Requirement(String type, Scope scope, int level) {

    public enum Scope {
        /** In the town that builds. */
        TOWN,
        /** In every non-captured town of the civilization (national wonders). */
        ALL_TOWNS,
        /** In every native town of the civilization (Burj al Arab, World Bank). */
        NATIVE_TOWNS;

        public static Scope parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }
}
