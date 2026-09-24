package com.civcraft.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses effect lists from balance YAML:
 * <pre>
 * effects:
 *   - {stat: culture, percent: 0.10, scope: civ}
 *   - {stat: hammers, add: 5}
 *   - {stat: tax_rate, max: 0.10}
 * </pre>
 */
public final class EffectParser {

    private EffectParser() {
    }

    public static List<Modifier> parse(Object raw, Scope defaultScope, String source) {
        List<Modifier> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Object statValue = map.get("stat");
            if (statValue == null) continue;
            String stat = statValue.toString().toLowerCase(Locale.ROOT);
            Scope scope = map.containsKey("scope")
                    ? Scope.valueOf(map.get("scope").toString().toUpperCase(Locale.ROOT)) : defaultScope;
            for (Op op : Op.values()) {
                Object value = map.get(op.name().toLowerCase(Locale.ROOT));
                if (value instanceof Number n) {
                    result.add(new Modifier(stat, op, n.doubleValue(), scope, source));
                }
            }
        }
        return result;
    }
}
