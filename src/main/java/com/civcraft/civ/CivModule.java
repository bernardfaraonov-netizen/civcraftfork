package com.civcraft.civ;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.chat.MuteRegistry;
import com.civcraft.core.text.Messages;
import com.civcraft.event.CivFormedEvent;
import com.civcraft.event.StructureCompletedEvent;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownModule;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Civilizations (spec §5, §6.1, §6.6, §7.3, §7.5, §8.7, §18, §19.1): founding with the flag, settlers,
 * province → civilization conversion, the /civ command tree, the /civ perm matrix, the market and
 * revolutions.
 */
public final class CivModule implements Module, Listener, MuteRegistry {

    private CivCraft civ;
    private final Map<String, CivData> data = new HashMap<>();
    private Founding founding;
    private Market market;

    @Override
    public String id() {
        return "civ";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("civ");
        civ.store().createCollection(CivData.COLLECTION);
        for (CivData d : civ.store().loadAll(CivData.COLLECTION, CivData.class)) {
            if (civ.state().civ(d.civId()) != null) data.put(d.civId(), d);
            else civ.saves().delete(CivData.COLLECTION, d.storageId());
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        founding = new Founding(civ, this);
        founding.enable();
        market = new Market(civ, this);
        new CivCommands(civ, this).register();
    }

    public Founding founding() {
        return founding;
    }

    public Market market() {
        return market;
    }

    public CivData data(Civilization c) {
        return data.computeIfAbsent(c.id(), CivData::new);
    }

    public void save(CivData d) {
        civ.saves().save(CivData.COLLECTION, d);
    }

    public void deleteData(Civilization c) {
        if (data.remove(c.id()) != null) civ.saves().delete(CivData.COLLECTION, c.id());
    }

    @Override
    public boolean isMuted(Civilization c, UUID player) {
        CivData d = data.get(c.id());
        if (d == null) return false;
        if (d.mutedPlayers().contains(player)) return true;
        Resident r = civ.state().resident(player);
        return r != null && r.townId() != null && d.mutedTowns().contains(r.townId());
    }

    /** Records a join/leave in the civ join log and announces it when the civ enabled the join log. */
    public void joinLog(Civilization c, String action, String name, String town) {
        CivData d = data(c);
        d.joinLog().add(Instant.now().getEpochSecond() + "|" + action + "|" + name + "|" + town);
        int max = civ.balance().getInt("civ", "join-log-size", 100);
        while (d.joinLog().size() > max) d.joinLog().removeFirst();
        save(d);
        if (c.flag("joinlog")) {
            Channels.civ(c, "civ.joinlog." + action, Messages.arg("name", name), Messages.arg("town", town));
        }
    }

    /**
     * A finished Capitol makes the province a civilization (spec §5.4) and replaces the town hall of that
     * town; a Capitol completed in another town moves the capital.
     */
    @EventHandler
    public void onStructureCompleted(StructureCompletedEvent e) {
        TownModule towns = civ.module(TownModule.class);
        if (!towns.service().capitolTypes().contains(e.type().toLowerCase(java.util.Locale.ROOT))) return;
        Town town = civ.state().town(e.townId());
        if (town == null) return;
        Civilization c = civ.state().civOf(town);
        if (c == null) return;
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api != null) {
            for (String hall : towns.service().townHallTypes()) {
                for (StructureApi.Placed p : List.copyOf(api.of(town, hall))) {
                    api.remove(p, false);
                    new StructureDestroyedEvent(p.id(), p.type(), town.id(), StructureDestroyedEvent.Cause.REPLACED).call();
                }
            }
        }
        if (!town.id().equals(c.capitalId())) {
            c.capitalId(town.id());
            Channels.civ(c, "civ.capital-moved", Messages.arg("town", town.name()));
        }
        if (c.isProvince()) {
            c.province(false);
            Channels.global("civ.formed", Messages.arg("civ", c.name()));
            new CivFormedEvent(c.id()).call();
        }
        civ.state().save(c);
        civ.stats().invalidate();
        towns.production().invalidate();
    }
}
