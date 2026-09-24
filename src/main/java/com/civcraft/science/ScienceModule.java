package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Money;
import com.civcraft.effect.StatSheet;
import com.civcraft.event.BeakersProducedEvent;
import com.civcraft.event.EraChangedEvent;
import com.civcraft.event.StructureCompletedEvent;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.event.TaxesConvertedEvent;
import com.civcraft.event.TechRemovedEvent;
import com.civcraft.event.TechResearchedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.space.SpaceApi;
import com.civcraft.town.TownApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Research, the tech tree and eras (spec 03 §1–3, spec 01 §5.3, §8.2). Science reaches research through
 * {@link BeakersProducedEvent} (hourly per town, credited at 1/60 per minute) and {@link #addBeakers}.
 */
public final class ScienceModule implements Module, ResearchApi, Listener {

    public static final String COLLECTION = "research";
    /** Civ-wide science percent applied to the sum of town science (spec 03 §1.3). */
    public static final String CIV_BEAKERS = "civ_beakers";
    public static final String RESEARCH_COST_COINS = "research_cost_coins";
    public static final String RESEARCH_COST_BEAKERS = "research_cost_beakers";
    public static final String RESEARCH_COST_MILITARY_COINS = "research_cost_military_coins";

    /** Final price of a research for a civ. */
    public record Cost(long coinsCents, double beakers, double coinDiscount, double beakerDiscount) {
    }

    private CivCraft civ;
    private TechTree tree;
    private WonderIndex wonders;
    private final Map<String, ResearchState> states = new HashMap<>();

    @Override
    public String id() {
        return "science";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("science");
        tree = new TechTree(civ.balance(), civ.logger());
        wonders = new WonderIndex(civ);
        civ.store().createCollection(COLLECTION);
        for (ResearchState st : civ.store().loadAll(COLLECTION, ResearchState.class)) {
            if (st.civId() == null || civ.state().civ(st.civId()) == null) {
                if (st.civId() != null) civ.saves().delete(COLLECTION, st.civId());
                continue;
            }
            st.repair();
            st.completed().removeIf(id -> !tree.exists(id));
            st.queue().removeIf(id -> !tree.exists(id));
            if (st.current() != null && !tree.exists(st.current())) st.current(null);
            states.put(st.civId(), st);
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        civ.stats().register(new ScienceEffects(civ, this));
        civ.clock().everyMinute("research", this::tick);
        for (Civilization c : civ.state().civs()) updateEra(c, false);
        registerScrolls();
        CivCommandGraft.add(civ, () -> ResearchCommands.build(civ, this));
    }

    // --- accessors --------------------------------------------------------------------------------

    public CivCraft civ() {
        return civ;
    }

    public TechTree tree() {
        return tree;
    }

    public WonderIndex wonders() {
        return wonders;
    }

    public ResearchState state(Civilization c) {
        ResearchState st = states.get(c.id());
        if (st == null) {
            st = new ResearchState(c.id());
            states.put(c.id(), st);
            civ.saves().save(COLLECTION, st);
        }
        return st;
    }

    private void save(ResearchState st) {
        civ.saves().save(COLLECTION, st);
    }

    private ConfigurationSection cfg() {
        return civ.balance().section("science", "research");
    }

    // --- ResearchApi ------------------------------------------------------------------------------

    @Override
    public boolean hasTech(Civilization c, String techId) {
        return c != null && state(c).completed().contains(techId);
    }

    @Override
    public Set<String> techs(Civilization c) {
        return Set.copyOf(state(c).completed());
    }

    @Override
    public int era(Civilization c) {
        return state(c).era();
    }

    @Override
    public boolean techExists(String techId) {
        return tree.exists(techId);
    }

    @Override
    public String techName(String techId) {
        TechTree.Tech t = tree.get(techId);
        return t == null ? techId : t.name();
    }

    @Override
    public void addBeakers(Civilization c, double beakers) {
        if (c == null || !Double.isFinite(beakers) || beakers <= 0) return;
        ResearchState st = state(c);
        credit(c, st, beakers, false);
        save(st);
    }

    @Override
    public double researchCost(Civilization c, String techId) {
        TechTree.Tech t = tree == null ? null : tree.get(techId);
        return t == null ? 0 : cost(c, t).beakers();
    }

    @Override
    public String currentTech(Civilization c) {
        return state(c).current();
    }

    @Override
    public double progress(Civilization c) {
        ResearchState st = state(c);
        TechTree.Tech t = tree.get(st.current());
        if (t == null) return 0;
        double cost = cost(c, t).beakers();
        return cost <= 0 ? 1 : Math.min(1, st.progress(t.id()) / cost);
    }

    @Override
    public boolean unlocked(Civilization c, String category, String id) {
        Set<String> gating = techsUnlocking(category, id);
        if (gating.isEmpty()) return true;
        if (c == null) return false;
        for (String t : gating) if (hasTech(c, t)) return true;
        return false;
    }

    @Override
    public Set<String> techsUnlocking(String category, String id) {
        Set<String> result = new HashSet<>(tree.techsUnlocking(category, id));
        if (category.equals("structures") || category.equals("wonders")) {
            String key = wonders.keyOfType(id);
            if (key != null) {
                result.addAll(tree.techsUnlocking("wonders", key));
                result.addAll(tree.techsUnlocking("structures", key));
            }
        }
        return result;
    }

    @Override
    public void removeLastTechs(Civilization c, int count) {
        ResearchState st = state(c);
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < count && !st.completed().isEmpty(); i++) {
            String id = st.completed().removeLast();
            st.researchedAt().remove(id);
            removed.add(id);
        }
        if (removed.isEmpty()) return;
        save(st);
        for (String id : removed) new TechRemovedEvent(c.id(), id).call();
        CivPerms.tellCiv(civ, c, "science.techs-lost", Messages.arg("techs", String.join(", ",
                removed.stream().map(this::techName).toList())));
        updateEra(c, true);
        civ.stats().invalidate();
    }

    @Override
    public int techEra(String techId) {
        TechTree.Tech t = tree.get(techId);
        return t == null ? -1 : t.era();
    }

    @Override
    public String eraName(int era) {
        return tree.era(era).name();
    }

    @Override
    public TextColor tagColor(Civilization c) {
        Integer fixed = c.fixedTagColor();
        return tree.era(fixed != null ? fixed : era(c)).color();
    }

    @Override
    public int leaderEra() {
        int max = 0;
        for (Civilization c : civ.state().civs()) max = Math.max(max, era(c));
        return max;
    }

    @Override
    public int capitolControlBlockHp(Civilization c) {
        ConfigurationSection s = civ.balance().section("science", "capitol-control-hp");
        int base = s.getInt("base", 50);
        int per = s.getInt("per-era", 10);
        int from = s.getInt("from-era", 2);
        int max = s.getInt("max", 100);
        return Math.min(max, base + per * Math.max(0, era(c) - from + 1));
    }

    @Override
    public double beakersPerHour(Civilization c) {
        return state(c).civRate();
    }

    // --- costs ------------------------------------------------------------------------------------

    /** Native towns founded by the civ (captured and bought towns excluded), at least 1. */
    public int foundedTowns(Civilization c) {
        int n = 0;
        for (Town t : civ.state().towns(c)) {
            if (t.status() == TownStatus.NATIVE && c.id().equals(t.nativeCivId())) n++;
        }
        return Math.max(1, n);
    }

    public Cost cost(Civilization c, TechTree.Tech t) {
        TechTree.CostFormula f = tree.formula();
        int civEra = era(c);
        int cities = foundedTowns(c);
        double eraPart = (t.era() + civEra / f.civEraDivisor()) * f.eraWeight();
        double coinMult = 1 + eraPart + (cities - 1) * f.cityCoins();
        double beakerMult = 1 + eraPart + (cities - 1) * f.cityBeakers();

        ConfigurationSection d = civ.balance().section("science", "discounts");
        double both = 0;
        double coinPct = 0;
        double beakerPct = 0;
        if (wonders.owns(c, "world_university")) both += d.getDouble("world-university", -0.20);
        int labs = 0;
        String labType = wonders.type("laboratory");
        for (Town town : civ.state().towns(c)) labs += wonders.count(town, labType);
        coinPct += Math.max(d.getDouble("laboratory-max", -0.25), labs * d.getDouble("laboratory-per-building", -0.05));
        int lag = leaderEra() - civEra;
        if (lag >= f.eraLagStart()) both -= f.eraLagPerEra() * (lag - 1);
        StatSheet sheet = civ.stats().civ(c);
        both += sheet.percent(com.civcraft.effect.Stats.RESEARCH_COST);
        coinPct += sheet.percent(RESEARCH_COST_COINS);
        beakerPct += sheet.percent(RESEARCH_COST_BEAKERS);
        TechTree.Branch branch = tree.branchDef(t.branch());
        if (branch != null && branch.military()) coinPct += sheet.percent(RESEARCH_COST_MILITARY_COINS);
        double coinDiscount = Math.max(-f.maxDiscount(), both + coinPct);
        double beakerDiscount = Math.max(-f.maxDiscount(), both + beakerPct);
        long coins = Money.ofCoins(t.coins() * coinMult * (1 + coinDiscount));
        double beakers = t.beakers() * beakerMult * (1 + beakerDiscount);
        return new Cost(Math.max(0, coins), Math.max(0, beakers), coinDiscount, beakerDiscount);
    }

    // --- research rules ---------------------------------------------------------------------------

    public boolean hasMainBuilding(Civilization c) {
        Town capital = civ.state().capital(c);
        if (capital == null) return false;
        if (wonders.api() == null) return true;
        List<String> types = cfg().getStringList(c.isProvince() ? "main-structures-province" : "main-structures-civ");
        if (types.isEmpty()) return true;
        for (String type : types) if (!wonders.completed(capital, wonders.type(type)).isEmpty()) return true;
        return false;
    }

    /** Validates that the civ may start the tech now; {@code checkCoins} also verifies the treasury. */
    public void checkCanResearch(Civilization c, TechTree.Tech t, boolean checkCoins) throws CivException {
        ResearchState st = state(c);
        if (st.completed().contains(t.id())) {
            throw new CivException("science.error.researched", Messages.arg("tech", t.name()));
        }
        List<String> missing = new ArrayList<>();
        for (String r : t.requires()) if (!st.completed().contains(r)) missing.add(techName(r));
        if (!missing.isEmpty()) {
            throw new CivException("science.error.requires", Messages.arg("tech", t.name()),
                    Messages.arg("techs", String.join(", ", missing)));
        }
        checkProvince(c, t);
        if (!hasMainBuilding(c)) throw new CivException("science.error.no-main-building");
        if (checkCoins) {
            long coins = cost(c, t).coinsCents();
            if (c.treasury() < coins) throw new CivException("science.error.no-coins", Messages.money("amount", coins));
        }
    }

    private void checkProvince(Civilization c, TechTree.Tech t) throws CivException {
        if (c.isProvince() && t.era() > cfg().getInt("province-max-era", 1) && !t.provinceAllowed()) {
            throw new CivException("science.error.province", Messages.arg("tech", t.name()),
                    Messages.arg("era", eraName(t.era())));
        }
    }

    /** Starts the tech (coins are taken from the civ treasury immediately and never refunded). */
    public void start(Civilization c, TechTree.Tech t) throws CivException {
        ResearchState st = state(c);
        checkCanResearch(c, t, true);
        long coins = cost(c, t).coinsCents();
        c.addTreasury(-coins);
        civ.state().save(c);
        st.current(t.id());
        st.queue().remove(t.id());
        double stored = st.storedBeakers();
        st.storedBeakers(0);
        save(st);
        CivPerms.tellCiv(civ, c, "science.started", Messages.arg("tech", t.name()), Messages.money("coins", coins));
        if (stored > 0) credit(c, st, stored, false);
        save(st);
    }

    /** Switches the current research; progress of the old tech is kept (or dropped per config). */
    public void change(Civilization c, TechTree.Tech t) throws CivException {
        ResearchState st = state(c);
        if (t.id().equals(st.current())) {
            throw new CivException("science.error.already-current", Messages.arg("tech", t.name()));
        }
        checkCanResearch(c, t, true);
        String old = st.current();
        if (old != null && !cfg().getBoolean("keep-progress-on-change", true)) st.progress(old, 0);
        st.current(null);
        start(c, t);
        if (old != null) {
            CivPerms.tellCiv(civ, c, "science.changed", Messages.arg("old", techName(old)), Messages.arg("tech", t.name()));
        }
    }

    public void queueAdd(Civilization c, TechTree.Tech t) throws CivException {
        ResearchState st = state(c);
        if (st.completed().contains(t.id())) {
            throw new CivException("science.error.researched", Messages.arg("tech", t.name()));
        }
        if (t.id().equals(st.current()) || st.queue().contains(t.id())) {
            throw new CivException("science.error.already-queued", Messages.arg("tech", t.name()));
        }
        if (st.queue().size() >= cfg().getInt("max-queue", 10)) {
            throw new CivException("science.error.queue-full", Messages.arg("max", cfg().getInt("max-queue", 10)));
        }
        Set<String> planned = new HashSet<>(st.completed());
        if (st.current() != null) planned.add(st.current());
        planned.addAll(st.queue());
        List<String> missing = new ArrayList<>();
        for (String r : t.requires()) if (!planned.contains(r)) missing.add(techName(r));
        if (!missing.isEmpty()) {
            throw new CivException("science.error.requires", Messages.arg("tech", t.name()),
                    Messages.arg("techs", String.join(", ", missing)));
        }
        checkProvince(c, t);
        st.queue().add(t.id());
        save(st);
        CivPerms.tellCiv(civ, c, "science.queued", Messages.arg("tech", t.name()),
                Messages.arg("position", st.queue().size()));
        if (st.current() == null) {
            tryStartQueue(c, st);
            save(st);
        }
    }

    public boolean queueRemove(Civilization c, TechTree.Tech t) {
        ResearchState st = state(c);
        boolean removed = t == null ? !st.queue().isEmpty() : st.queue().contains(t.id());
        if (t == null) st.queue().clear();
        else st.queue().remove(t.id());
        // Drop queued techs whose prerequisites are no longer planned.
        Set<String> planned = new HashSet<>(st.completed());
        if (st.current() != null) planned.add(st.current());
        List<String> kept = new ArrayList<>();
        for (String id : st.queue()) {
            TechTree.Tech q = tree.get(id);
            if (q != null && planned.containsAll(q.requires())) {
                kept.add(id);
                planned.add(id);
            }
        }
        st.queue().clear();
        st.queue().addAll(kept);
        save(st);
        return removed;
    }

    /** Starts the first startable tech of the queue. Returns true when something started. */
    private boolean tryStartQueue(Civilization c, ResearchState st) {
        while (st.current() == null && !st.queue().isEmpty()) {
            TechTree.Tech next = tree.get(st.queue().getFirst());
            if (next == null || st.completed().contains(next.id())) {
                st.queue().removeFirst();
                continue;
            }
            try {
                checkCanResearch(c, next, false);
            } catch (CivException e) {
                st.queue().removeFirst();
                CivPerms.tellCiv(civ, c, "science.queue-skipped", Messages.arg("tech", next.name()));
                continue;
            }
            long coins = cost(c, next).coinsCents();
            if (c.treasury() < coins) {
                Instant warned = st.queueWarnedAt();
                long minutes = cfg().getLong("queue-warning-minutes", 60);
                if (warned == null || Duration.between(warned, Instant.now()).toMinutes() >= minutes) {
                    st.queueWarnedAt(Instant.now());
                    CivPerms.tellCiv(civ, c, "science.queue-waiting", Messages.arg("tech", next.name()),
                            Messages.money("coins", coins));
                }
                return false;
            }
            try {
                start(c, next);
                st.queueWarnedAt(null);
                return true;
            } catch (CivException e) {
                st.queue().removeFirst();
            }
        }
        return false;
    }

    /** Adds beakers to the current research; without one they go to the stored pool. */
    private void credit(Civilization c, ResearchState st, double beakers, boolean capStored) {
        if (beakers <= 0 || !Double.isFinite(beakers)) return;
        if (st.current() == null) tryStartQueue(c, st);
        TechTree.Tech t = tree.get(st.current());
        if (t == null) {
            st.current(null);
            double stored = st.storedBeakers() + beakers;
            double capHours = cfg().getDouble("stored-beakers-hours-cap", 24);
            if (capStored && capHours > 0) stored = Math.min(stored, Math.max(st.storedBeakers(), st.civRate() * capHours));
            st.storedBeakers(stored);
            return;
        }
        double cost = cost(c, t).beakers();
        double total = st.progress(t.id()) + beakers;
        if (total >= cost) {
            double overflow = total - Math.max(0, cost);
            st.progress(t.id(), 0);
            complete(c, st, t);
            if (overflow > 0) credit(c, st, overflow, capStored);
            return;
        }
        st.progress(t.id(), total);
        int stepPercent = Math.max(1, cfg().getInt("progress-step-percent", 5));
        int step = (int) Math.floor(total / cost * 100 / stepPercent);
        if (step > st.announcedStep()) {
            st.announcedStep(step);
            CivPerms.tellCiv(civ, c, "science.progress", Messages.arg("tech", t.name()),
                    Messages.arg("percent", Math.min(100, step * stepPercent)));
        }
    }

    private void complete(Civilization c, ResearchState st, TechTree.Tech t) {
        st.current(null);
        if (!st.completed().contains(t.id())) st.completed().add(t.id());
        st.researchedAt().put(t.id(), Instant.now());
        st.queue().remove(t.id());
        save(st);
        CivPerms.broadcast(civ, "science.researched", Messages.arg("civ", c.name()), Messages.arg("tech", t.name()));
        new TechResearchedEvent(c.id(), t.id()).call();
        updateEra(c, true);
        civ.stats().invalidate();
        tryStartQueue(c, st);
    }

    /** Admin: grant a tech instantly. */
    public void grant(Civilization c, TechTree.Tech t) {
        ResearchState st = state(c);
        if (st.completed().contains(t.id())) return;
        if (t.id().equals(st.current())) st.current(null);
        complete(c, st, t);
        save(st);
    }

    /** Admin: remove a specific tech. */
    public boolean revoke(Civilization c, TechTree.Tech t) {
        ResearchState st = state(c);
        if (!st.completed().remove(t.id())) return false;
        st.researchedAt().remove(t.id());
        save(st);
        new TechRemovedEvent(c.id(), t.id()).call();
        updateEra(c, true);
        civ.stats().invalidate();
        return true;
    }

    private void updateEra(Civilization c, boolean announce) {
        ResearchState st = state(c);
        int era = tree.eras().getFirst().index();
        for (String id : st.completed()) {
            TechTree.Tech t = tree.get(id);
            if (t != null) era = Math.max(era, t.era());
        }
        int old = st.era();
        if (old == era) return;
        st.era(era);
        save(st);
        new EraChangedEvent(c.id(), old, era).call();
        if (announce && era > old) {
            CivPerms.broadcast(civ, "science.era-reached", Messages.arg("civ", c.name()),
                    Messages.arg("era", Component.text(eraName(era), tree.era(era).color())));
        }
    }

    // --- production -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeakers(BeakersProducedEvent event) {
        Civilization c = civ.state().civ(event.civId());
        if (c == null || event.townId() == null) return;
        double b = event.beakers();
        if (!Double.isFinite(b) || b < 0) b = 0;
        ResearchState st = state(c);
        st.townRates().put(event.townId(), b);
        save(st);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTaxes(TaxesConvertedEvent event) {
        Civilization c = civ.state().civ(event.civId());
        if (c == null || event.coinsCents() < 0 || !Double.isFinite(event.beakers())) return;
        ResearchState st = state(c);
        st.recordTaxes(event.townId(), event.coinsCents(), Math.max(0, event.beakers()));
        save(st);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStructureCompleted(StructureCompletedEvent event) {
        civ.stats().invalidate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStructureDestroyed(StructureDestroyedEvent event) {
        civ.stats().invalidate();
    }

    /** Hourly science of a town as last reported (or as computed by the town module). */
    public double townRate(Civilization c, Town town) {
        Double r = state(c).townRates().get(town.id());
        if (r != null) return r;
        TownApi towns = civ.apiOrNull(TownApi.class);
        if (towns == null) return 0;
        double v = towns.beakersPerHour(town);
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    /** Civ-wide science multiplier (Great Library, Angkor Wat, World University, ultra buff, talent 5.3). */
    public double civMultiplier(Civilization c) {
        return Math.max(0, civ.stats().civ(c).apply(CIV_BEAKERS, 1.0));
    }

    private void tick() {
        SpaceApi space = civ.apiOrNull(SpaceApi.class);
        for (Civilization c : List.copyOf(civ.state().civs())) {
            ResearchState st = state(c);
            Set<String> townIds = new LinkedHashSet<>();
            double hourly = 0;
            for (Town town : civ.state().towns(c)) {
                townIds.add(town.id());
                hourly += townRate(c, town);
            }
            st.townRates().keySet().retainAll(townIds);
            st.taxCoinsLastHour().keySet().retainAll(townIds);
            st.taxBeakersLastHour().keySet().retainAll(townIds);
            hourly *= civMultiplier(c);
            st.civRate(hourly);
            double minute = hourly / 60.0;
            if (minute > 0) {
                if (space != null && space.missionActive(c)) space.addMissionBeakers(c, minute);
                else credit(c, st, minute, true);
            } else if (st.current() == null) {
                tryStartQueue(c, st);
            }
            save(st);
        }
    }

    // --- scrolls ----------------------------------------------------------------------------------

    private void registerScrolls() {
        CivItems items = CivItems.get(civ);
        ConfigurationSection scrolls = civ.balance().section("science", "scrolls");
        for (String id : scrolls.getKeys(false)) {
            ConfigurationSection s = scrolls.getConfigurationSection(id);
            if (s == null) continue;
            Material m = Material.matchMaterial(s.getString("material", "PAPER"));
            items.define(id, m == null ? Material.PAPER : m, Component.text(s.getString("name", id)),
                    civ.messages().lines("science.scroll-lore", Messages.arg("eras",
                            String.join(", ", s.getIntegerList("eras").stream().map(this::eraName).toList())),
                            Messages.arg("max", s.getInt("max-percent", 25))), 16);
            items.onUse(id, (player, event) -> useScroll(player, event, s));
        }
    }

    private void useScroll(Player player, PlayerInteractEvent event, ConfigurationSection s) {
        try {
            Civilization c = CivPerms.civOf(civ, player);
            ResearchState st = state(c);
            TechTree.Tech t = tree.get(st.current());
            List<Integer> eras = s.getIntegerList("eras");
            if (t == null) throw new CivException("science.error.scroll-no-research");
            if (!eras.contains(t.era())) {
                throw new CivException("science.error.scroll-era", Messages.arg("eras",
                        String.join(", ", eras.stream().map(this::eraName).toList())));
            }
            int min = Math.max(0, s.getInt("min-percent", 1));
            int max = Math.max(min, s.getInt("max-percent", 25));
            int percent = ThreadLocalRandom.current().nextInt(min, max + 1);
            double beakers = cost(c, t).beakers() * percent / 100.0;
            ItemStack hand = event.getItem();
            if (hand == null) return;
            hand.setAmount(hand.getAmount() - 1);
            credit(c, st, beakers, false);
            save(st);
            CivPerms.tellCiv(civ, c, "science.scroll-used", Messages.arg("player", player.getName()),
                    Messages.arg("percent", percent), Messages.arg("tech", t.name()));
        } catch (CivException e) {
            civ.messages().send(player, e.key(), e.args());
        }
    }

    // --- display helpers --------------------------------------------------------------------------

    /** Estimated hours to finish the given beakers at the civ's current rate, or -1. */
    public double hoursFor(Civilization c, double beakers) {
        double rate = state(c).civRate();
        return rate <= 0 ? -1 : beakers / rate;
    }

    public String formatHours(double hours) {
        if (hours < 0) return "∞";
        return com.civcraft.core.util.Durations.format(Duration.ofSeconds((long) Math.min(hours * 3600, 1e12)));
    }

    /** Available techs: not researched, prerequisites met, allowed for provinces. */
    public List<TechTree.Tech> available(Civilization c) {
        List<TechTree.Tech> result = new ArrayList<>();
        ResearchState st = state(c);
        for (TechTree.Tech t : tree.all()) {
            if (st.completed().contains(t.id()) || t.id().equals(st.current())) continue;
            if (!st.completed().containsAll(t.requires())) continue;
            try {
                checkProvince(c, t);
            } catch (CivException e) {
                continue;
            }
            result.add(t);
        }
        return result;
    }

    public String percent(double fraction) {
        return Format.percent(fraction);
    }
}
