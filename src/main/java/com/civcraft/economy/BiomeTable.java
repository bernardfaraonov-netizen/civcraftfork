package com.civcraft.economy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Per-culture-chunk biome bonuses (spec §9.2) with the 1.21 → legacy row mapping and the chunk classes
 * used by nations and wonders (desert, jungle, ocean, river). Loaded from {@code balance/biomes.yml}.
 */
public final class BiomeTable {

    /** Values one culture chunk of this biome adds to its town. */
    public record Values(double hammers, double growth, double happiness, double beakers) {
        public static final Values ZERO = new Values(0, 0, 0, 0);

        public Values plus(Values o) {
            return new Values(hammers + o.hammers, growth + o.growth, happiness + o.happiness, beakers + o.beakers);
        }
    }

    private final Map<String, Values> rows = new HashMap<>();
    private final Map<String, String> mapping = new HashMap<>();
    private final Map<String, Set<String>> classes = new HashMap<>();

    public BiomeTable(ConfigurationSection file) {
        ConfigurationSection r = file.getConfigurationSection("rows");
        if (r != null) {
            for (String key : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(key);
                if (s == null) continue;
                rows.put(key.toLowerCase(Locale.ROOT), new Values(s.getDouble("hammers"), s.getDouble("growth"),
                        s.getDouble("happiness"), s.getDouble("beakers")));
            }
        }
        ConfigurationSection m = file.getConfigurationSection("mapping");
        if (m != null) {
            for (String key : m.getKeys(false)) mapping.put(key.toLowerCase(Locale.ROOT), m.getString(key, "").toLowerCase(Locale.ROOT));
        }
        ConfigurationSection c = file.getConfigurationSection("classes");
        if (c != null) {
            for (String key : c.getKeys(false)) {
                Set<String> set = new HashSet<>();
                for (String b : c.getStringList(key)) set.add(b.toLowerCase(Locale.ROOT));
                classes.put(key.toLowerCase(Locale.ROOT), set);
            }
        }
    }

    /** Bonus values for a 1.21 biome key such as {@code minecraft:plains} or {@code plains}. */
    public Values values(String biome) {
        if (biome == null) return Values.ZERO;
        String key = strip(biome);
        String row = mapping.getOrDefault(key, key);
        return rows.getOrDefault(row, Values.ZERO);
    }

    /** Whether the biome belongs to a chunk class ("desert", "jungle", "ocean", "river"). */
    public boolean inClass(String biome, String cls) {
        if (biome == null) return false;
        Set<String> set = classes.get(cls.toLowerCase(Locale.ROOT));
        return set != null && set.contains(strip(biome));
    }

    public Set<String> classNames() {
        return classes.keySet();
    }

    public List<String> rowNames() {
        return List.copyOf(rows.keySet());
    }

    private static String strip(String biome) {
        String b = biome.toLowerCase(Locale.ROOT);
        int colon = b.indexOf(':');
        return colon >= 0 ? b.substring(colon + 1) : b;
    }
}
