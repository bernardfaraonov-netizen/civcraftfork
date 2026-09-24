package com.civcraft.item;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Persistent data keys of CivCraft items. Items are identified only by {@link #ITEM}. */
public final class ItemKeys {

    public static final String NAMESPACE = "civcraft";

    /** Custom item id (STRING). */
    public static final NamespacedKey ITEM = key("item");
    /** Hash of the static definition the stack was rendered from (INTEGER); drives re-rendering. */
    public static final NamespacedKey REVISION = key("rev");
    /**
     * Sharpening (catalyst enhancement) level (INTEGER): attack for weapons and bows, defense for armor.
     * Ruin recipe scrolls (PvE module) clear it.
     */
    public static final NamespacedKey SHARPEN = key("enhancement");
    /** Gear tier 0..4 (INTEGER), read by the mob module (weapon tier rule) and ruin recipe scrolls. */
    public static final NamespacedKey TIER = key("tier");
    /** «Небесная кара» level (INTEGER) mirrored for the world boss damage code of the PvE module. */
    public static final NamespacedKey HEAVENLY_PUNISHMENT = key("heavenly_punishment");
    /** Ruin recipe applied by the PvE module (STRING, e.g. turtle_shell). */
    public static final NamespacedKey RECIPE = key("recipe");
    /** Marks the world boss entity (PvE module); the item combat formula leaves it alone. */
    public static final NamespacedKey WORLD_BOSS = key("world_boss");
    /** Custom enchantments (TAG_CONTAINER of enchant id → INTEGER level). */
    public static final NamespacedKey ENCHANTS = key("enchants");
    /** SoulBound flag (BOOLEAN); works on vanilla items too. */
    public static final NamespacedKey SOULBOUND = key("soulbound");
    /** One-shot copy of an item, e.g. artifacts from ruins (BYTE 1; shared with the science module). */
    public static final NamespacedKey SINGLE_USE = key("single_use");
    /** Marks a vanilla stack as not repairable (donation pickaxes; BOOLEAN). */
    public static final NamespacedKey NO_REPAIR = key("no_repair");

    // Arrow data, copied from the bow at shot time.
    public static final NamespacedKey ARROW_BOW = key("arrow_bow");
    public static final NamespacedKey ARROW_FORCE = key("arrow_force");
    public static final NamespacedKey ARROW_SHARPEN = key("arrow_sharpen");
    public static final NamespacedKey ARROW_ENCHANTS = key("arrow_enchants");
    public static final NamespacedKey ARROW_VANILLA = key("arrow_vanilla");

    // Player attribute modifiers owned by the gear service.
    public static final NamespacedKey MOD_ARMOR = key("gear_armor");
    public static final NamespacedKey MOD_HEALTH = key("gear_health");
    public static final NamespacedKey MOD_SPEED = key("gear_speed");
    public static final NamespacedKey MOD_ATTACK_SPEED = key("no_attack_cooldown");
    public static final NamespacedKey MOD_WEAPON = key("weapon_damage");

    private ItemKeys() {
    }

    public static NamespacedKey key(String value) {
        return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + value), value);
    }
}
