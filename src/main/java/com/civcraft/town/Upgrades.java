package com.civcraft.town;

import com.civcraft.CivCraft;
import com.civcraft.core.util.Money;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Registry of town upgrades bought with {@code /t upgrade buy} (spec §6.3): money from the town
 * treasury first, then hammers collected over time. Town levels are built in from
 * {@code core.yml town.levels}; other modules (bank levels, crusher levels...) register their own
 * upgrades with {@link #register} and receive a completion callback.
 */
public final class Upgrades {

    /**
     * @param id        stable id ({@code town_level_3}, {@code bank_level_2})
     * @param name      display name (Russian) used in lists and matched by /t upgrade buy
     * @param category  grouping for /t upgrade list
     * @param cost      price in hundredths
     * @param hammers   hammers required after purchase
     * @param tech      required technology id or null
     * @param requires  upgrade that must be completed first, or null
     */
    public record Def(String id, String name, String category, long cost, double hammers, String tech, String requires) {
    }

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<String, BiConsumer<Town, Def>> handlers = new LinkedHashMap<>();

    void loadBuiltIns(CivCraft civ, BiConsumer<Town, Def> townLevelHandler) {
        ConfigurationSection levels = civ.balance().section("core", "town.levels");
        List<Integer> keys = new ArrayList<>();
        for (String k : levels.getKeys(false)) keys.add(Integer.parseInt(k));
        Collections.sort(keys);
        for (int level : keys) {
            if (level <= 1) continue;
            ConfigurationSection s = levels.getConfigurationSection(String.valueOf(level));
            if (s == null) continue;
            String requires = level > 2 ? "town_level_" + (level - 1) : null;
            register(new Def("town_level_" + level, s.getString("name", "Level " + level), "town",
                    Money.ofCoins(s.getDouble("cost")), s.getDouble("hammers"), s.getString("tech"), requires), townLevelHandler);
        }
        ConfigurationSection extra = civ.balance().section("town", "upgrades");
        for (String id : extra.getKeys(false)) {
            ConfigurationSection s = extra.getConfigurationSection(id);
            if (s == null) continue;
            register(new Def(id, s.getString("name", id), s.getString("category", "other"), Money.ofCoins(s.getDouble("cost")),
                    s.getDouble("hammers"), s.getString("tech"), s.getString("requires")), (t, d) -> { });
        }
    }

    /** Registers (or replaces) an upgrade and the action applied when its hammers are complete. */
    public void register(Def def, BiConsumer<Town, Def> onComplete) {
        defs.put(def.id(), def);
        handlers.put(def.id(), onComplete);
    }

    public Def get(String id) {
        return defs.get(id);
    }

    public Collection<Def> all() {
        return Collections.unmodifiableCollection(defs.values());
    }

    void complete(Town town, Def def) {
        BiConsumer<Town, Def> handler = handlers.get(def.id());
        if (handler != null) handler.accept(town, def);
    }
}
