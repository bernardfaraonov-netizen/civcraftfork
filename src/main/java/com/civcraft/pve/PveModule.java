package com.civcraft.pve;

import com.civcraft.CivCraft;
import com.civcraft.Module;

/**
 * Shared PvE infrastructure used by the mob, valley, dungeon, event and ruin modules: coin drops,
 * special areas and the WarTime probe. Must be registered before those modules.
 */
public final class PveModule implements Module {

    private Coins coins;
    private AreaService areas;

    @Override
    public String id() {
        return "pve";
    }

    @Override
    public void load(CivCraft civ) {
        civ.messages().include("pve");
        civ.balance().file("pve");
        areas = new AreaService(civ);
        areas.load();
        coins = new Coins(civ);
    }

    @Override
    public void enable(CivCraft civ) {
        var cfg = civ.balance().file("pve");
        coins.configure(cfg.getString("coins.material", "GOLD_NUGGET"), cfg.getBoolean("coins.pickup-message", false));
        WarTime.load(civ.plugin().getConfig(), civ.settings().zone(), civ.logger());
        civ.listen(coins);
        civ.listen(areas);
        civ.clock().everySecond("pve-areas", areas::tick);
        areas.registerAdmin();
    }

    public Coins coins() {
        return coins;
    }

    public AreaService areas() {
        return areas;
    }
}
