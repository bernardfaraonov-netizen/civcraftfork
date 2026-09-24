package com.civcraft.structure.type;

import java.util.Locale;
import org.bukkit.block.Biome;

/** Placement rule of water structures (spec 02 §1.3: shipyard, lighthouse, trade ship, boats). */
public enum WaterRule {
    NONE,
    /** Any water at sea level. */
    WATER,
    /** Only ocean biome chunks (shipyard). */
    OCEAN,
    /** River or ocean biome (trade ship). */
    RIVER_OR_OCEAN;

    public boolean isWater() {
        return this != NONE;
    }

    /** Whether the biome satisfies the rule (NONE and WATER accept any biome). */
    public boolean acceptsBiome(Biome biome) {
        String key = biome.getKey().getKey();
        return switch (this) {
            case NONE, WATER -> true;
            case OCEAN -> key.contains("ocean");
            case RIVER_OR_OCEAN -> key.contains("ocean") || key.contains("river");
        };
    }

    public static WaterRule parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
