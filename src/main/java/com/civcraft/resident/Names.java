package com.civcraft.resident;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import java.util.regex.Pattern;

/** Validation of town, civilization, camp and group names (spec §1: 3–20 Latin characters, tag ≤ 5). */
public final class Names {

    private Names() {
    }

    /** Validates a town/civ/camp name; Cyrillic typed in the Russian layout is converted first. */
    public static String name(String input) throws CivException {
        String value = normalize(input);
        CivCraft civ = CivCraft.get();
        int min = civ.balance().getInt("core", "names.min-length", 3);
        int max = civ.balance().getInt("core", "names.max-length", 20);
        String pattern = civ.balance().file("core").getString("names.pattern", "^[A-Za-z0-9_]+$");
        if (value.length() < min || value.length() > max) {
            throw new CivException("names.length", Messages.arg("min", min), Messages.arg("max", max));
        }
        if (!Pattern.matches(pattern, value)) throw new CivException("names.latin");
        return value;
    }

    /** Validates a civilization tag: up to 5 Latin letters. */
    public static String tag(String input) throws CivException {
        String value = normalize(input);
        int max = CivCraft.get().balance().getInt("core", "names.tag-max-length", 5);
        if (value.isEmpty() || value.length() > max || !value.chars().allMatch(c -> c < 128 && Character.isLetter(c))) {
            throw new CivException("names.tag", Messages.arg("max", max));
        }
        return value;
    }

    /** Validates a group name (Latin, no spaces). */
    public static String group(String input) throws CivException {
        String value = normalize(input).toLowerCase(java.util.Locale.ROOT);
        if (value.length() < 2 || value.length() > 20 || !value.matches("^[a-z0-9_]+$")) {
            throw new CivException("names.group");
        }
        return value;
    }

    private static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        return Layout.hasCyrillic(value) ? Layout.toLatin(value) : value;
    }
}
