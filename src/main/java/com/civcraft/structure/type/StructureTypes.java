package com.civcraft.structure.type;

import com.civcraft.balance.Balance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** Registry of all structure types from {@code balance/structures.yml} and {@code balance/wonders.yml}. */
public final class StructureTypes {

    private final Map<String, StructureType> types = new LinkedHashMap<>();
    private final Map<String, List<String>> nationAliases = new LinkedHashMap<>();

    public StructureTypes(Balance balance, Logger logger) {
        for (String file : List.of("structures", "wonders")) {
            ConfigurationSection section = balance.section(file, "types");
            for (String id : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(id);
                if (s == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                try {
                    types.put(key, StructureType.load(key, s, logger));
                } catch (RuntimeException e) {
                    logger.severe("Invalid structure type " + id + " in " + file + ".yml: " + e.getMessage());
                }
            }
        }
        ConfigurationSection nations = balance.section("structures", "nations");
        for (String nation : nations.getKeys(false)) {
            List<String> aliases = new ArrayList<>();
            aliases.add(nation.toLowerCase(Locale.ROOT));
            for (String a : nations.getStringList(nation)) aliases.add(a.toLowerCase(Locale.ROOT));
            nationAliases.put(nation.toLowerCase(Locale.ROOT), aliases);
        }
        validate(logger);
    }

    private void validate(Logger logger) {
        for (StructureType t : types.values()) {
            for (Requirement r : t.requires()) {
                if (!types.containsKey(r.type())) logger.warning("Structure " + t.id() + " requires unknown type " + r.type());
            }
            if (t.replaces() != null && !types.containsKey(t.replaces())) {
                logger.warning("Structure " + t.id() + " replaces unknown type " + t.replaces());
            }
            if (t.nation() != null && !nationAliases.containsKey(t.nation())) {
                logger.warning("Structure " + t.id() + " has unknown nation " + t.nation());
            }
        }
    }

    public StructureType get(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String id) {
        return get(id) != null;
    }

    public Collection<StructureType> all() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** Types sorted for menus: category order, then era, then name. */
    public List<StructureType> sorted() {
        List<StructureType> list = new ArrayList<>(types.values());
        list.sort(Comparator.comparingInt((StructureType t) -> t.category().ordinal())
                .thenComparingInt(StructureType::era).thenComparing(StructureType::name));
        return list;
    }

    /** Whether a civ nation id (as stored by the civ module) is the given balance nation. */
    public boolean nationMatches(String balanceNation, String civNation) {
        if (balanceNation == null) return true;
        if (civNation == null) return false;
        List<String> aliases = nationAliases.getOrDefault(balanceNation, List.of(balanceNation));
        return aliases.contains(civNation.toLowerCase(Locale.ROOT));
    }

    /** The nation-exclusive type that replaces {@code base} for the nation, or null. */
    public StructureType replacementFor(String base, String civNation) {
        if (civNation == null) return null;
        for (StructureType t : types.values()) {
            if (base.equals(t.replaces()) && nationMatches(t.nation(), civNation)) return t;
        }
        return null;
    }

    /** Type ids that satisfy a requirement for {@code type}: itself and its nation replacements. */
    public List<String> satisfying(String type) {
        List<String> ids = new ArrayList<>();
        ids.add(type);
        for (StructureType t : types.values()) if (type.equals(t.replaces())) ids.add(t.id());
        return ids;
    }

    /** Finds types by what a player typed. */
    public List<StructureType> match(String query, Collection<StructureType> candidates) {
        return NameMatcher.best(query, candidates, t -> {
            List<String> keys = new ArrayList<>(t.aliases().size() + 2);
            keys.add(t.id());
            keys.add(t.name());
            keys.addAll(t.aliases());
            return keys;
        });
    }
}
