package com.civcraft.pve;

import org.bukkit.NamespacedKey;

/** PDC keys shared by the PvE modules. All live in the {@code civcraft} namespace. */
public final class PveKeys {

    private PveKeys() {
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("civcraft", name);
    }

    /** Custom mob type id on the entity (see {@code mob.MobType}). */
    public static final NamespacedKey MOB_TYPE = key("mob_type");
    /** Custom mob tier 1..4 on the entity. */
    public static final NamespacedKey MOB_TIER = key("mob_tier");
    /** Number of Angry Yobo waves this Yobo already summoned. */
    public static final NamespacedKey YOBO_WAVES = key("yobo_waves");
    /** Epoch millis of the last Angry Yobo wave. */
    public static final NamespacedKey YOBO_LAST_WAVE = key("yobo_last_wave");
    /** Epoch millis until which a Behemoth is aggressive toward its current target. */
    public static final NamespacedKey AGGRO_UNTIL = key("aggro_until");
    /** Rat spawn marker index. */
    public static final NamespacedKey RAT_MARKER = key("rat_marker");
    /** Marks the world boss entity. */
    public static final NamespacedKey BOSS = key("world_boss");
    /** Town id of a plague slime. */
    public static final NamespacedKey PLAGUE_TOWN = key("plague_town");

    /** Coin value (hundredths) of a dropped coin item. */
    public static final NamespacedKey COINS = key("coins");

    /**
     * Equipment tier written by the item module on weapons and armour (int 0..4). Read-only here; the
     * mob module falls back to a vanilla material table when it is absent.
     */
    public static final NamespacedKey ITEM_TIER = key("tier");
    /** Custom item id written by the item module ({@code civcraft:item}). */
    public static final NamespacedKey ITEM_ID = key("item");
    /** Weapon tier copied onto arrows when they are shot. */
    public static final NamespacedKey ARROW_TIER = key("arrow_tier");
    /** Item id of the bow that shot an arrow. */
    public static final NamespacedKey ARROW_BOW = key("arrow_bow");
    /** Ruin recipe id copied onto arrows. */
    public static final NamespacedKey ARROW_RECIPE = key("arrow_recipe");

    /** Special PvE item id (lucky block, scrolls, ruin weapons): {@code ruins.PveItems}. */
    public static final NamespacedKey PVE_ITEM = key("pve_item");
    /** Numeric payload of a scroll (percent, hammers...). */
    public static final NamespacedKey SCROLL_VALUE = key("scroll_value");
    /** Recipe scroll applied to an equipment item (§15.4). */
    public static final NamespacedKey RECIPE = key("recipe");
    /** Marks an item that cannot be repaired. */
    public static final NamespacedKey NO_REPAIR = key("no_repair");

    /** Player: serialized location to return to when leaving the valley or the dungeon. */
    public static final NamespacedKey RETURN_POINT = key("pve_return");
    /** Player: logged out inside the boss zone. */
    public static final NamespacedKey LEFT_IN_BOSS_ZONE = key("left_boss_zone");
    /** Player: starter kit already given. */
    public static final NamespacedKey STARTER_KIT = key("starter_kit");
    /** Entity spawned by a CivCraft PvE system (excluded from ClearLag unless it is a mob). */
    public static final NamespacedKey KEEP = key("clearlag_keep");
}
