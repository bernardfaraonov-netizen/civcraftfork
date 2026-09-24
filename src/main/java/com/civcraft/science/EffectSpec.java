package com.civcraft.science;

import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An effect entry from balance YAML with the extras used by techs, talents, religions and ultra buffs:
 * <pre>
 * - {stat: beakers, add: 20, scope: town, per: science_buildings}   # × count in each town
 * - {stat: happiness, add: 1, per: world_wonders, divisor: 3}       # × floor(count / 3)
 * - {stat: beaker_price, add: -2, unless-government: [mercantilism]}
 * - {stat: player_damage, add: 1, min-talents: 5}
 * - {stat: captured_unhappiness, multiply: 0, binary: true}         # not scaled by religion strength
 * </pre>
 */
public record EffectSpec(String stat, Op op, double value, Scope scope, String per, int divisor,
                         Set<String> unlessGovernment, int minTalents, boolean binary) {

    public static List<EffectSpec> parse(Object raw, Scope defaultScope) {
        List<EffectSpec> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Object statValue = map.get("stat");
            if (statValue == null) continue;
            String stat = statValue.toString().toLowerCase(Locale.ROOT);
            Scope scope = defaultScope;
            if (map.containsKey("scope")) {
                try {
                    scope = Scope.valueOf(map.get("scope").toString().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    java.util.logging.Logger.getLogger("CivCraft").warning("Unknown effect scope " + map.get("scope"));
                }
            }
            String per = map.containsKey("per") ? map.get("per").toString() : null;
            int divisor = map.get("divisor") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            Set<String> unless = new HashSet<>();
            if (map.get("unless-government") instanceof List<?> govs) {
                for (Object g : govs) unless.add(String.valueOf(g).toLowerCase(Locale.ROOT));
            }
            int minTalents = map.get("min-talents") instanceof Number n ? n.intValue() : 0;
            boolean binary = Boolean.TRUE.equals(map.get("binary"));
            for (Op op : Op.values()) {
                Object value = map.get(op.name().toLowerCase(Locale.ROOT));
                if (value instanceof Number n) {
                    result.add(new EffectSpec(stat, op, n.doubleValue(), scope, per, divisor, unless, minTalents, binary));
                }
            }
        }
        return result;
    }

    /** Modifier with the base value multiplied by {@code factor} (MULTIPLY/MIN/MAX are not scaled). */
    public Modifier modifier(double factor, String source) {
        Modifier m = new Modifier(stat, op, value, scope, source);
        return factor == 1.0 ? m : m.scaled(factor);
    }

    /** Multiplier for a counter value: {@code floor(count / divisor)}. */
    public double perFactor(int count) {
        return Math.floorDiv(count, divisor);
    }

    /**
     * Routes the effect: TOWN scope stays in the given town (when there is one), everything else is
     * contributed as a civ-wide modifier.
     */
    public void apply(com.civcraft.effect.EffectSink sink, com.civcraft.model.Civilization civ,
                      com.civcraft.model.Town town, double factor, String source) {
        Modifier m = modifier(factor, source);
        if (scope == Scope.TOWN && town != null) sink.town(town, m);
        else sink.civ(civ, m);
    }

    public boolean appliesTo(String government) {
        return government == null || !unlessGovernment.contains(government.toLowerCase(Locale.ROOT));
    }
}
