package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.structure.StructureApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Read-only view of wonders and other structures the science family depends on. Wonder "keys" are
 * stable names used in our balance files (great_library, notre_dame...); they map to the structure
 * module's type ids through {@code balance/science.yml → wonders}. All lookups go through
 * {@link StructureApi} and return nothing when the structure module is absent.
 */
public final class WonderIndex {

    private final CivCraft civ;
    private final Map<String, String> worldWonders = new LinkedHashMap<>();
    private final Map<String, String> nationalWonders = new LinkedHashMap<>();
    private final Map<String, String> keyByType = new HashMap<>();
    private final Map<String, String> structureTypes = new HashMap<>();
    private final Map<String, List<String>> structureGroups = new HashMap<>();

    public WonderIndex(CivCraft civ) {
        this.civ = civ;
        ConfigurationSection world = civ.balance().section("science", "wonders.world");
        for (String key : world.getKeys(false)) put(worldWonders, key, world.getString(key, key));
        ConfigurationSection national = civ.balance().section("science", "wonders.national");
        for (String key : national.getKeys(false)) put(nationalWonders, key, national.getString(key, key));
        ConfigurationSection types = civ.balance().section("science", "structure-types");
        for (String key : types.getKeys(false)) {
            if (types.isList(key)) structureGroups.put(key, types.getStringList(key));
            else structureTypes.put(key, types.getString(key, key));
        }
    }

    private void put(Map<String, String> map, String key, String type) {
        String t = type.toLowerCase(Locale.ROOT);
        map.put(key, t);
        keyByType.put(t, key);
    }

    public StructureApi api() {
        return civ.apiOrNull(StructureApi.class);
    }

    /** Structure type id of a wonder key or of a named structure role (capitol, tavern...). */
    public String type(String key) {
        String t = worldWonders.get(key);
        if (t == null) t = nationalWonders.get(key);
        if (t == null) t = structureTypes.get(key);
        return t != null ? t : key;
    }

    public List<String> group(String name) {
        return structureGroups.getOrDefault(name, List.of());
    }

    /** Wonder key of a structure type, or null. */
    public String keyOfType(String type) {
        return type == null ? null : keyByType.get(type.toLowerCase(Locale.ROOT));
    }

    public boolean isWorldWonderType(String type) {
        String key = keyOfType(type);
        return key != null && worldWonders.containsKey(key);
    }

    public boolean isNationalWonderType(String type) {
        String key = keyOfType(type);
        return key != null && nationalWonders.containsKey(key);
    }

    public Collection<String> worldWonderKeys() {
        return worldWonders.keySet();
    }

    /** Completed structures of the type in the town. */
    public List<? extends StructureApi.Placed> completed(Town town, String type) {
        StructureApi api = api();
        if (api == null || town == null) return List.of();
        return api.of(town, type).stream().filter(StructureApi.Placed::complete).toList();
    }

    public int count(Town town, String type) {
        return completed(town, type).size();
    }

    public int countAll(Town town, Collection<String> types) {
        int n = 0;
        for (String t : types) n += count(town, t);
        return n;
    }

    /** Whether the town has a completed wonder with the given key. */
    public boolean has(Town town, String wonderKey) {
        return !completed(town, type(wonderKey)).isEmpty();
    }

    /** First town of the civ that holds the completed wonder, or null. */
    public Town townWith(Civilization c, String wonderKey) {
        return townWith(c, wonderKey, t -> true);
    }

    public Town townWith(Civilization c, String wonderKey, Predicate<Town> filter) {
        if (c == null) return null;
        for (Town t : civ.state().towns(c)) {
            if (filter.test(t) && has(t, wonderKey)) return t;
        }
        return null;
    }

    public boolean owns(Civilization c, String wonderKey) {
        return townWith(c, wonderKey) != null;
    }

    /** Wonder keys (world and national) completed in the town. */
    public Set<String> keysIn(Town town) {
        Set<String> result = new HashSet<>();
        StructureApi api = api();
        if (api == null || town == null) return result;
        for (StructureApi.Placed p : api.of(town)) {
            if (!p.complete()) continue;
            String key = keyOfType(p.type());
            if (key != null) result.add(key);
        }
        return result;
    }

    /** Completed world wonders in the whole world (national wonders excluded). */
    public int worldWondersInWorld() {
        StructureApi api = api();
        if (api == null) return 0;
        int n = 0;
        for (Town t : civ.state().towns()) {
            for (StructureApi.Placed p : api.of(t)) {
                if (p.complete() && isWorldWonderType(p.type())) n++;
            }
        }
        return n;
    }

    /** Completed world wonders held by the civ in towns accepted by the filter. */
    public int worldWonders(Civilization c, Predicate<Town> filter, Set<String> excludedKeys) {
        StructureApi api = api();
        if (api == null) return 0;
        int n = 0;
        for (Town t : civ.state().towns(c)) {
            if (!filter.test(t)) continue;
            for (StructureApi.Placed p : api.of(t)) {
                if (!p.complete() || !isWorldWonderType(p.type())) continue;
                if (excludedKeys.contains(keyOfType(p.type()))) continue;
                n++;
            }
        }
        return n;
    }
}
