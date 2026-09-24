package com.civcraft.core;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * A rule violation that should be shown to the player (not a bug). Carries a message key from the
 * language file. Commands and GUIs catch it and print the localized text.
 */
public class CivException extends Exception {

    private final String key;
    private final transient TagResolver[] args;

    public CivException(String key, TagResolver... args) {
        super(key, null, false, false);
        this.key = key;
        this.args = args;
    }

    public String key() {
        return key;
    }

    public TagResolver[] args() {
        return args;
    }

    public static void check(boolean condition, String key, TagResolver... args) throws CivException {
        if (!condition) throw new CivException(key, args);
    }
}
