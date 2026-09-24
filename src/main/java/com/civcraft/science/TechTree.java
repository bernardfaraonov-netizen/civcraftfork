package com.civcraft.science;

import com.civcraft.balance.Balance;
import com.civcraft.effect.Scope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** The technology tree loaded from {@code balance/techs.yml} (spec 03 §3). */
public final class TechTree {

    public record Tech(String id, String name, String branch, int era, double coins, double beakers,
                       List<String> requires, boolean provinceAllowed, Material icon,
                       Map<String, List<String>> unlocks, List<EffectSpec> effects, List<String> description,
                       int order) {

        public boolean isWonder() {
            return "wonders".equals(branch);
        }
    }

    public record Era(int index, String name, TextColor color) {
    }

    public record Branch(String id, String name, Material icon, boolean military) {
    }

    /** Parameters of the cost formula (spec 03 §1.2). */
    public record CostFormula(double eraWeight, double civEraDivisor, double cityCoins, double cityBeakers,
                              double maxDiscount, int eraLagStart, double eraLagPerEra) {
    }

    private final Map<String, Tech> techs = new LinkedHashMap<>();
    private final Map<String, String> byName = new HashMap<>();
    private final Map<String, Set<String>> unlockIndex = new HashMap<>();
    private final List<Era> eras = new ArrayList<>();
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private final CostFormula formula;

    public TechTree(Balance balance, Logger logger) {
        ConfigurationSection cost = balance.section("techs", "cost");
        formula = new CostFormula(cost.getDouble("era-weight", 0.11), cost.getDouble("civ-era-divisor", 10),
                cost.getDouble("city-coins", 0.05), cost.getDouble("city-beakers", 0.075),
                cost.getDouble("max-discount", 0.75), cost.getInt("era-lag.start", 2),
                cost.getDouble("era-lag.per-era", 0.10));

        ConfigurationSection eraSection = balance.section("techs", "eras");
        for (String key : eraSection.getKeys(false)) {
            ConfigurationSection s = eraSection.getConfigurationSection(key);
            if (s == null) continue;
            TextColor color = NamedTextColor.NAMES.valueOr(s.getString("color", "white").toLowerCase(Locale.ROOT),
                    NamedTextColor.WHITE);
            eras.add(new Era(Integer.parseInt(key), s.getString("name", key), color));
        }
        eras.sort((a, b) -> Integer.compare(a.index(), b.index()));
        if (eras.isEmpty()) eras.add(new Era(0, "0", NamedTextColor.WHITE));

        ConfigurationSection branchSection = balance.section("techs", "branches");
        for (String key : branchSection.getKeys(false)) {
            ConfigurationSection s = branchSection.getConfigurationSection(key);
            if (s == null) continue;
            branches.put(key, new Branch(key, s.getString("name", key), material(s.getString("icon"), Material.BOOK),
                    s.getBoolean("military", false)));
        }

        ConfigurationSection techSection = balance.section("techs", "techs");
        Map<String, Integer> declaredEra = new HashMap<>();
        int order = 0;
        for (String id : techSection.getKeys(false)) {
            ConfigurationSection s = techSection.getConfigurationSection(id);
            if (s == null) continue;
            Map<String, List<String>> unlocks = new LinkedHashMap<>();
            ConfigurationSection u = s.getConfigurationSection("unlocks");
            if (u != null) {
                for (String cat : u.getKeys(false)) {
                    List<String> ids = new ArrayList<>();
                    for (String v : u.getStringList(cat)) ids.add(v.toLowerCase(Locale.ROOT));
                    unlocks.put(cat, List.copyOf(ids));
                }
            }
            if (s.contains("era")) declaredEra.put(id, s.getInt("era"));
            Tech t = new Tech(id, s.getString("name", id), s.getString("branch", "progress"), s.getInt("era", -1),
                    Math.max(0, s.getDouble("coins")), Math.max(0, s.getDouble("beakers")),
                    List.copyOf(s.getStringList("requires")), s.getBoolean("province", false),
                    material(s.getString("icon"), Material.PAPER), Collections.unmodifiableMap(unlocks),
                    EffectSpec.parse(s.getList("effects"), Scope.CIV), List.copyOf(s.getStringList("description")),
                    order++);
            techs.put(id, t);
        }
        // Validate requirements and derive eras of wonder techs (max era of their requirements).
        for (Tech t : List.copyOf(techs.values())) {
            for (String r : t.requires()) {
                if (!techs.containsKey(r)) logger.warning("Tech " + t.id() + " requires unknown tech " + r);
            }
        }
        for (Tech t : List.copyOf(techs.values())) {
            if (t.era() < 0) {
                techs.put(t.id(), withEra(t, deriveEra(t.id(), declaredEra, new HashSet<>())));
            }
        }
        for (Tech t : techs.values()) {
            byName.put(t.name().toLowerCase(Locale.ROOT), t.id());
            for (Map.Entry<String, List<String>> e : t.unlocks().entrySet()) {
                for (String v : e.getValue()) {
                    unlockIndex.computeIfAbsent(e.getKey() + ":" + v, k -> new HashSet<>()).add(t.id());
                }
            }
        }
        logger.info("Loaded " + techs.size() + " technologies");
    }

    private int deriveEra(String id, Map<String, Integer> declared, Set<String> visiting) {
        Integer d = declared.get(id);
        if (d != null) return d;
        if (!visiting.add(id)) return 0;
        Tech t = techs.get(id);
        int era = 0;
        if (t != null) for (String r : t.requires()) era = Math.max(era, deriveEra(r, declared, visiting));
        declared.put(id, era);
        return era;
    }

    private static Tech withEra(Tech t, int era) {
        return new Tech(t.id(), t.name(), t.branch(), era, t.coins(), t.beakers(), t.requires(), t.provinceAllowed(),
                t.icon(), t.unlocks(), t.effects(), t.description(), t.order());
    }

    private static Material material(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() ? m : def;
    }

    public Tech get(String id) {
        return id == null ? null : techs.get(id);
    }

    public boolean exists(String id) {
        return id != null && techs.containsKey(id);
    }

    public Collection<Tech> all() {
        return Collections.unmodifiableCollection(techs.values());
    }

    public List<Tech> branch(String branch) {
        List<Tech> result = new ArrayList<>();
        for (Tech t : techs.values()) if (t.branch().equals(branch)) result.add(t);
        result.sort((a, b) -> a.era() != b.era() ? Integer.compare(a.era(), b.era()) : Integer.compare(a.order(), b.order()));
        return result;
    }

    public Collection<Branch> branches() {
        return branches.values();
    }

    public Branch branchDef(String id) {
        return branches.get(id);
    }

    public Set<String> techsUnlocking(String category, String id) {
        return unlockIndex.getOrDefault(category + ":" + id.toLowerCase(Locale.ROOT), Set.of());
    }

    /** Resolves a tech from user input: id, Russian name, or a unique prefix of either. */
    public Tech find(String input) {
        if (input == null) return null;
        String q = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String qName = input.trim().toLowerCase(Locale.ROOT);
        if (techs.containsKey(q)) return techs.get(q);
        String byExactName = byName.get(qName);
        if (byExactName != null) return techs.get(byExactName);
        Tech found = null;
        for (Tech t : techs.values()) {
            if (t.id().startsWith(q) || t.name().toLowerCase(Locale.ROOT).startsWith(qName)) {
                if (found != null) return null;
                found = t;
            }
        }
        return found;
    }

    public List<Era> eras() {
        return eras;
    }

    public Era era(int index) {
        for (Era e : eras) if (e.index() == index) return e;
        return index < eras.getFirst().index() ? eras.getFirst() : eras.getLast();
    }

    public int maxEra() {
        return eras.getLast().index();
    }

    public CostFormula formula() {
        return formula;
    }
}
