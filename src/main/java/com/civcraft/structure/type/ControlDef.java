package com.civcraft.structure.type;

/**
 * Control block settings of a type. HP = {@code min(max, hp + perEra × max(0, era − eraFrom))} before the
 * {@code stat} modifiers (castles, talents, Chichen Itza...). With {@code inheritMain} the HP of the town's main
 * building is used (Neuschwanstein).
 */
public record ControlDef(int hp, int perEra, int eraFrom, int max, boolean inheritMain, String stat) {

    public int baseHp(int era) {
        if (perEra <= 0) return hp;
        int value = hp + perEra * Math.max(0, era - eraFrom);
        return max > 0 ? Math.min(max, value) : value;
    }
}
