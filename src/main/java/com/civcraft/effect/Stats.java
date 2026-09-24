package com.civcraft.effect;

/**
 * Well-known stat ids. Modules may introduce more; these are the ones shared across modules.
 * Values are per hour unless stated otherwise.
 */
public final class Stats {

    private Stats() {
    }

    public static final String HAMMERS = "hammers";
    public static final String BEAKERS = "beakers";
    public static final String CULTURE = "culture";
    public static final String GROWTH = "growth";
    public static final String HAPPINESS = "happiness";
    public static final String UNHAPPINESS = "unhappiness";
    /** Hourly coin income of the town (buildings, trade goods...). */
    public static final String INCOME = "income";
    public static final String FAITH = "faith";
    /** Multiplier applied to the daily upkeep of the town. */
    public static final String UPKEEP = "upkeep";
    /** Extra tax rate cap (fraction) for the civ. */
    public static final String MAX_TAX = "max_tax";
    /** Coins per beaker when converting taxes. */
    public static final String BEAKER_PRICE = "beaker_price";
    /** Research cost multiplier. */
    public static final String RESEARCH_COST = "research_cost";
    /** Culture needed for the next level multiplier. */
    public static final String CULTURE_REQUIREMENT = "culture_requirement";
    /** Additional improvement (tile) slots. */
    public static final String SLOTS = "slots";
    /** Control block HP bonus. */
    public static final String CONTROL_HP = "control_hp";
    /** Bank exchange multiplier. */
    public static final String BANK_RATE = "bank_rate";
    /** War upkeep reduction (fraction). */
    public static final String WAR_UPKEEP_REDUCTION = "war_upkeep_reduction";
    /** Structure cost multiplier. */
    public static final String BUILD_COST = "build_cost";
    /** Upgrade cost multiplier. */
    public static final String UPGRADE_COST = "upgrade_cost";
    /** Cottage income multiplier. */
    public static final String COTTAGE = "cottage";
    /** Cottage consumption multiplier. */
    public static final String COTTAGE_CONSUMPTION = "cottage_consumption";
    /** Mine hammer multiplier. */
    public static final String MINE = "mine";
    /** Trade ship income multiplier. */
    public static final String TRADE_SHIP = "trade_ship";
}
