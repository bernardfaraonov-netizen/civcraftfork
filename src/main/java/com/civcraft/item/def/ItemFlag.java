package com.civcraft.item.def;

import java.util.Locale;

/** Optional behaviour switches of an item definition ({@code flags:} list in YAML). */
public enum ItemFlag {
    /** Kept in the inventory on death (like the SoulBound enchantment). */
    SOULBOUND,
    /** Kept on death without being called SoulBound (artifacts, units, founding flag). */
    KEEP_ON_DEATH,
    /** Never loses durability. */
    UNBREAKABLE,
    /** May be placed as a block. */
    PLACEABLE,
    /** May not be thrown out of the inventory. */
    NO_DROP,
    /** May not be put into chests and other storage. */
    NO_CONTAINER,
    /** Cannot be repaired (donation picks, lucky iron pickaxe). */
    NO_REPAIR,
    /** Cannot receive enchantments from libraries and wonders (valley gear). */
    NO_ENCHANT,
    /** Uses vanilla durability (ruin tools) instead of "loses durability only on death". */
    VANILLA_DURABILITY,
    /** Keeps the vanilla right-click action of its base item. */
    VANILLA_USE,
    /** Can be eaten/drunk like its base item. */
    CONSUMABLE,
    /** The item may be used as an ingredient of vanilla recipes (off by default). */
    VANILLA_CRAFTING;

    public static ItemFlag parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
