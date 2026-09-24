package com.civcraft.government;

import com.civcraft.effect.EffectParser;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Scope;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One government type (spec §13.1). Multipliers are fractions (1.0 = 100 %). {@code effects} are
 * additional special modifiers (crusher efficiency, upgrade discounts...).
 */
public record GovernmentDef(String id, String name, String tech, boolean selectable,
                            double trade, double strategic, double cottage, double upkeep, double growth,
                            double maxTax, double culture, double hammers, double science, double tradeShip,
                            double income, double beakerPrice, boolean talentBeakerDiscount, List<Modifier> effects,
                            List<String> description) {

    public static GovernmentDef read(String id, ConfigurationSection s) {
        return new GovernmentDef(id, s.getString("name", id), s.getString("tech"), s.getBoolean("selectable", true),
                pct(s, "trade"), pct(s, "strategic"), pct(s, "cottage"), pct(s, "upkeep"), pct(s, "growth"),
                s.getDouble("max-tax", 0) / 100.0, pct(s, "culture"), pct(s, "hammers"), pct(s, "science"),
                pct(s, "trade-ship"), pct(s, "income"), s.getDouble("beaker-price", 17.5),
                s.getBoolean("talent-beaker-discount", true),
                EffectParser.parse(s.getList("effects"), Scope.CIV, "government:" + id),
                s.getStringList("description"));
    }

    private static double pct(ConfigurationSection s, String key) {
        return s.getDouble(key, 100) / 100.0;
    }
}
