package com.civcraft.resident;

import java.util.HashMap;
import java.util.Map;

/**
 * Russian ЙЦУКЕН → QWERTY keyboard layout converter (spec §1: "/сшм" → "/civ", names typed in the
 * wrong layout are found as well).
 */
public final class Layout {

    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюё";
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static final Map<Character, Character> MAP = new HashMap<>();

    static {
        for (int i = 0; i < RU.length(); i++) {
            char ru = RU.charAt(i);
            char en = EN.charAt(i);
            MAP.put(ru, en);
            MAP.put(Character.toUpperCase(ru), Character.toUpperCase(en));
        }
    }

    private Layout() {
    }

    /** Whether the string contains at least one Cyrillic letter of the layout. */
    public static boolean hasCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (MAP.containsKey(s.charAt(i))) return true;
        }
        return false;
    }

    /** Converts every Cyrillic layout character to the Latin key in the same position. */
    public static String toLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character mapped = MAP.get(c);
            sb.append(mapped == null ? c : mapped);
        }
        return sb.toString();
    }
}
