package com.civcraft.government;

import com.civcraft.effect.EffectParser;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A nation (spec §14). Static effects apply to every town of the civilization; the computed ones
 * depend on culture chunks, structures and era:
 * <ul>
 *   <li>{@code per-chunk}: modifier × number of culture chunks of a class (Egyptians: +1 hammer per desert chunk);</li>
 *   <li>{@code town-with-chunk-class}: civ-wide modifier per town having at least one chunk of the class;</li>
 *   <li>{@code native-town-effects}: only for towns with status NATIVE (Americans: +3 slots);</li>
 *   <li>{@code capital-effects}: only for the capital (French: −5 % culture requirement);</li>
 *   <li>{@code per-structure}: modifier × number of complete structures of the given types;</li>
 *   <li>{@code per-structure-era}: modifier × complete structures × civilization era (Japanese culture).</li>
 * </ul>
 */
public record NationDef(String id, String name, String government, double governmentChangeMultiplier,
                        List<Modifier> effects, List<Modifier> nativeTownEffects, List<Modifier> capitalEffects,
                        List<ClassBonus> perChunk, List<ClassBonus> townWithChunkClass,
                        List<StructureBonus> perStructure, List<StructureBonus> perStructureEra, List<String> description) {

    public record ClassBonus(String chunkClass, Modifier modifier) {
    }

    public record StructureBonus(List<String> types, Modifier modifier) {
    }

    public static NationDef read(String id, ConfigurationSection s) {
        String source = "nation:" + id;
        return new NationDef(id, s.getString("name", id), s.getString("government"),
                s.getDouble("government-change-multiplier", 1.0),
                EffectParser.parse(s.getList("effects"), Scope.CIV, source),
                EffectParser.parse(s.getList("native-town-effects"), Scope.TOWN, source),
                EffectParser.parse(s.getList("capital-effects"), Scope.TOWN, source),
                classBonuses(s.getList("per-chunk"), Scope.TOWN, source),
                classBonuses(s.getList("town-with-chunk-class"), Scope.CIV, source),
                structureBonuses(s.getList("per-structure"), source),
                structureBonuses(s.getList("per-structure-era"), source),
                s.getStringList("description"));
    }

    private static List<ClassBonus> classBonuses(Object raw, Scope scope, String source) {
        List<ClassBonus> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map) || map.get("class") == null) continue;
            for (Modifier m : EffectParser.parse(List.of(map), scope, source)) {
                result.add(new ClassBonus(String.valueOf(map.get("class")), m));
            }
        }
        return result;
    }

    private static List<StructureBonus> structureBonuses(Object raw, String source) {
        List<StructureBonus> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map) || !(map.get("types") instanceof List<?> types)) continue;
            List<String> ids = types.stream().map(String::valueOf).toList();
            for (Modifier m : EffectParser.parse(List.of(map), Scope.TOWN, source)) result.add(new StructureBonus(ids, m));
        }
        return result;
    }
}
