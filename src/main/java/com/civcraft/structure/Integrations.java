package com.civcraft.structure;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.effect.Stats;
import com.civcraft.item.ItemApi;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.science.ResearchApi;
import com.civcraft.town.TownApi;
import org.bukkit.entity.Player;

/**
 * Optional APIs of modules written in parallel (towns, research, items) with fallbacks, so the structure module
 * works alone. Looked up lazily because modules are enabled after this one.
 */
final class Integrations {

    private final CivCraft civ;
    private final double fallbackHammers;

    Integrations(CivCraft civ, double fallbackHammers) {
        this.civ = civ;
        this.fallbackHammers = fallbackHammers;
    }

    TownApi towns() {
        return civ.apiOrNull(TownApi.class);
    }

    ResearchApi research() {
        return civ.apiOrNull(ResearchApi.class);
    }

    ItemApi items() {
        return civ.apiOrNull(ItemApi.class);
    }

    double hammersPerHour(Town town) {
        TownApi api = towns();
        if (api == null) return civ.stats().town(town, Stats.HAMMERS, fallbackHammers);
        double h = api.hammersPerHour(town);
        return Double.isFinite(h) ? Math.max(0, h) : 0;
    }

    boolean hasTech(Town town, String tech) {
        ResearchApi api = research();
        if (api == null) return true;
        Civilization c = civ.state().civOf(town);
        return c != null && api.hasTech(c, tech);
    }

    String techName(String tech) {
        ResearchApi api = research();
        return api == null ? tech : api.techName(tech);
    }

    int era(Civilization c) {
        ResearchApi api = research();
        return api == null || c == null ? 0 : Math.max(0, api.era(c));
    }

    /** Improvement slots of the town (spec 02 §1.1). */
    int slots(Town town) {
        TownApi api = towns();
        if (api != null) return api.slots(town);
        int base = civ.balance().getInt("core", "town.levels." + town.level() + ".slots", 4);
        return (int) Math.floor(civ.stats().town(town, Stats.SLOTS, base));
    }

    /** Mayor, assistant or civ leader acting for the town. */
    boolean canManage(Player player, Town town) {
        TownApi api = towns();
        if (api != null) return api.canManage(player, town);
        if (town.isOfficial(player.getUniqueId())) return true;
        Civilization c = civ.state().civOf(town);
        return c != null && c.isLeader(player.getUniqueId());
    }

    /** The town the player manages: selected town, else own town. */
    Town selectedTown(Player player) throws CivException {
        TownApi api = towns();
        if (api != null) return api.selectedTown(player);
        Resident r = civ.state().resident(player);
        Town town = r == null ? null : civ.state().town(r.selectedTownId() != null ? r.selectedTownId() : r.townId());
        if (town == null) throw new CivException("error.not-in-town");
        return town;
    }

    /** {@code /civ perm} check with a leader fallback. */
    void checkCivPerm(Player player, Civilization c, String perm) throws CivException {
        TownApi api = towns();
        if (api != null) {
            api.checkCivPerm(player, c, perm);
            return;
        }
        if (c == null || !c.isLeader(player.getUniqueId())) throw new CivException("error.no-permission");
    }

    int cultureLevel(Town town) {
        TownApi api = towns();
        return api != null ? api.cultureLevel(town) : civ.culture().level(town);
    }
}
