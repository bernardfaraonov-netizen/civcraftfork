package com.civcraft.structure.type;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A functional marker a procedural template must contain, e.g. {@code chest 1} or {@code control}. */
public record MarkerSpec(String type, List<String> args) {

    public static MarkerSpec parse(String value) {
        String[] parts = value.trim().split("\\s+");
        return new MarkerSpec(parts[0].toLowerCase(Locale.ROOT).replace("/", ""),
                List.copyOf(Arrays.asList(parts).subList(1, parts.length)));
    }
}
