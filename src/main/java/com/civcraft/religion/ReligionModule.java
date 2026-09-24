package com.civcraft.religion;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.clock.GameClock;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.Stats;
import com.civcraft.event.ReligionChangedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.science.CivCommandGraft;
import com.civcraft.science.CivItems;
import com.civcraft.science.CivPerms;
import com.civcraft.science.EffectSpec;
import com.civcraft.science.ScienceModule;
import com.civcraft.science.WarWindow;
import com.civcraft.science.WonderIndex;
import com.civcraft.town.TownApi;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Religion (spec 01 §16): religion points, adoption and change, the per-religion rating and buff
 * strength, loss conditions, the Prophet unit and religion-point purchases.
 */
public final class ReligionModule implements Module, ReligionApi, EffectProvider {

    public static final String COLLECTION = "religion";
    public static final String RATING_COLLECTION = "religion_rating";
    public static final String META_COLLECTION = "religion_meta";

    record Rule(String type, double amount, double fraction, String structure, String nation) {
        static Rule of(ConfigurationSection s) {
            if (s == null) return new Rule("none", 0, 0, null, null);
            return new Rule(s.getString("type", "none"), s.getDouble("amount"), s.getDouble("fraction"),
                    s.getString("structure"), s.getString("nation"));
        }
    }

    record Religion(String id, String name, Material icon, List<String> description, Rule requirement, Rule loss,
                    List<EffectSpec> effects) {
    }

    private CivCraft civ;
    private WonderIndex wonders;
    private WarWindow war;
    private final Map<String, Religion> religions = new LinkedHashMap<>();
    private final Map<String, ReligionState> states = new HashMap<>();
    private final Map<String, ReligionRating> ratings = new HashMap<>();
    private ReligionMeta meta = new ReligionMeta();

    @Override
    public String id() {
        return "religion";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("religion");
        ConfigurationSection rs = civ.balance().section("religion", "religions");
        for (String id : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(id);
            if (s == null) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "BOOK"));
            religions.put(id, new Religion(id, s.getString("name", id), icon == null ? Material.BOOK : icon,
                    s.getStringList("description"), Rule.of(s.getConfigurationSection("requirement")),
                    Rule.of(s.getConfigurationSection("loss")), EffectSpec.parse(s.getList("effects"), Scope.CIV)));
        }
        civ.store().createCollection(COLLECTION);
        civ.store().createCollection(RATING_COLLECTION);
        civ.store().createCollection(META_COLLECTION);
        for (ReligionState st : civ.store().loadAll(COLLECTION, ReligionState.class)) {
            if (st.civId() == null || civ.state().civ(st.civId()) == null) {
                if (st.civId() != null) civ.saves().delete(COLLECTION, st.civId());
                continue;
            }
            st.repair();
            if (st.religion() != null && !religions.containsKey(st.religion())) st.religion(null);
            states.put(st.civId(), st);
        }
        for (ReligionRating r : civ.store().loadAll(RATING_COLLECTION, ReligionRating.class)) {
            if (r.religionId() == null || !religions.containsKey(r.religionId())) continue;
            r.repair();
            ratings.put(r.religionId(), r);
        }
        List<ReligionMeta> metas = civ.store().loadAll(META_COLLECTION, ReligionMeta.class);
        if (!metas.isEmpty()) meta = metas.getFirst();
        // Consistency: every follower appears exactly once in its religion's rating.
        for (ReligionRating r : ratings.values()) {
            r.order().removeIf(id -> {
                ReligionState st = states.get(id);
                return st == null || !r.religionId().equals(st.religion());
            });
            List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(r.order()));
            r.order().clear();
            r.order().addAll(unique);
        }
        for (ReligionState st : states.values()) {
            if (st.religion() != null && !rating(st.religion()).order().contains(st.civId())) {
                rating(st.religion()).order().add(st.civId());
            }
        }
    }

    @Override
    public void enable(CivCraft civ) {
        wonders = new WonderIndex(civ);
        war = new WarWindow(civ);
        civ.stats().register(this);
        civ.clock().everyMinute("religion", this::tick);
        civ.clock().daily(GameClock.TAXES, "religion-daily", this::daily);
        CivItems items = CivItems.get(civ);
        ConfigurationSection prophet = civ.balance().section("religion", "prophet");
        String prophetId = prophet.getString("item", "prophet");
        Material mat = Material.matchMaterial(prophet.getString("material", "TOTEM_OF_UNDYING"));
        items.define(prophetId, mat == null ? Material.TOTEM_OF_UNDYING : mat,
                civ.messages().component("religion.prophet.name"),
                civ.messages().lines("religion.prophet.lore", Messages.arg("points", prophet.getInt("points", 300))), 1);
        items.onUse(prophetId, this::useProphet);
        CivCommandGraft.add(civ, this::command);
    }

    // --- state ------------------------------------------------------------------------------------

    ReligionState state(Civilization c) {
        return states.computeIfAbsent(c.id(), id -> {
            ReligionState st = new ReligionState(id);
            civ.saves().save(COLLECTION, st);
            return st;
        });
    }

    private void save(ReligionState st) {
        civ.saves().save(COLLECTION, st);
    }

    private ReligionRating rating(String religionId) {
        return ratings.computeIfAbsent(religionId, ReligionRating::new);
    }

    private void saveRating(String religionId) {
        civ.saves().save(RATING_COLLECTION, rating(religionId));
    }

    Religion find(String input) {
        if (input == null) return null;
        String q = input.trim().toLowerCase(Locale.ROOT);
        for (Religion r : religions.values()) {
            if (r.id().equals(q) || r.name().toLowerCase(Locale.ROOT).equals(q)) return r;
        }
        Religion found = null;
        for (Religion r : religions.values()) {
            if (r.id().startsWith(q) || r.name().toLowerCase(Locale.ROOT).startsWith(q)) {
                if (found != null) return null;
                found = r;
            }
        }
        return found;
    }

    private ConfigurationSection cfg() {
        return civ.balance().file("religion");
    }

    // --- ReligionApi ------------------------------------------------------------------------------

    @Override
    public double points(Civilization c) {
        return state(c).points();
    }

    @Override
    public boolean spend(Civilization c, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        ReligionState st = state(c);
        if (st.points() + 1e-9 < amount) return false;
        st.points(st.points() - amount);
        save(st);
        return true;
    }

    @Override
    public void add(Civilization c, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return;
        ReligionState st = state(c);
        st.points(st.points() + amount);
        save(st);
    }

    @Override
    public double remove(Civilization c, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return 0;
        ReligionState st = state(c);
        double taken = Math.min(st.points(), amount);
        st.points(st.points() - taken);
        save(st);
        return taken;
    }

    @Override
    public String religion(Civilization c) {
        return state(c).religion();
    }

    @Override
    public String religionName(String religionId) {
        Religion r = religionId == null ? null : religions.get(religionId);
        return r == null ? civ.messages().plain("religion.none") : r.name();
    }

    @Override
    public int place(Civilization c) {
        String r = religion(c);
        if (r == null) return 0;
        int idx = rating(r).order().indexOf(c.id());
        return idx < 0 ? 0 : idx + 1;
    }

    @Override
    public double strength(Civilization c) {
        return strengthForPlace(place(c));
    }

    double strengthForPlace(int place) {
        if (place <= 0) return 0;
        if (place == 1) return 1;
        ConfigurationSection r = civ.balance().section("religion", "rating");
        if (place > r.getInt("max-place", 16)) return 0;
        double pct = 100 - (r.getDouble("offset", 10) + r.getDouble("per-place", 5) * place);
        return Math.max(0, pct) / 100.0;
    }

    @Override
    public double purchasePrice(double hammers) {
        ConfigurationSection p = civ.balance().section("religion", "purchase");
        return Math.max(0, hammers) * p.getDouble("factor", 0.75) * (1 - p.getDouble("discount", 0.60));
    }

    @Override
    public void checkCanTrainProphet(Civilization c, Town town) throws CivException {
        if (religion(c) == null) throw new CivException("religion.error.prophet-no-religion");
        boolean inTown = cfg().getBoolean("prophet.require-notre-dame-in-town", true);
        boolean ok = inTown && town != null ? wonders.has(town, "notre_dame") : wonders.owns(c, "notre_dame");
        if (!ok) throw new CivException("religion.error.prophet-no-notre-dame");
    }

    @Override
    public double foundingCost(Civilization c) {
        ScienceModule science = civ.module(ScienceModule.class);
        int towns = science.foundedTowns(c);
        return cfg().getDouble("founding.base-cost", 10000) * (1 + cfg().getDouble("founding.per-town", 0.075) * towns);
    }

    // --- requirements -----------------------------------------------------------------------------

    private boolean uncaptured(Town t) {
        return t.status() != TownStatus.CAPTURED;
    }

    double uncapturedScience(Civilization c) {
        ScienceModule science = civ.module(ScienceModule.class);
        double sum = 0;
        for (Town t : civ.state().towns(c)) if (uncaptured(t)) sum += science.townRate(c, t);
        return sum;
    }

    private double uncapturedCulture(Civilization c) {
        double sum = 0;
        for (Town t : civ.state().towns(c)) if (uncaptured(t)) sum += t.culture();
        return sum;
    }

    private int uncapturedStructures(Civilization c, String role) {
        String type = wonders.type(role);
        int n = 0;
        for (Town t : civ.state().towns(c)) if (uncaptured(t)) n += wonders.count(t, type);
        return n;
    }

    private double capitalUnhappiness(Civilization c) {
        Town capital = civ.state().capital(c);
        return capital == null ? 0 : civ.stats().town(capital).get(Stats.UNHAPPINESS);
    }

    /** Current value of the requirement's measure (for display and checks). */
    double requirementValue(Civilization c, Rule r) {
        return switch (r.type()) {
            case "beakers_per_hour" -> civ.module(ScienceModule.class).beakersPerHour(c);
            case "culture_uncaptured" -> uncapturedCulture(c);
            case "towns" -> civ.state().towns(c).size();
            case "structures" -> uncapturedStructures(c, r.structure());
            default -> 0;
        };
    }

    boolean meets(Civilization c, Rule r) {
        return switch (r.type()) {
            case "nation" -> r.nation() != null && r.nation().equalsIgnoreCase(c.nation());
            case "none" -> true;
            default -> requirementValue(c, r) >= r.amount();
        };
    }

    private void checkRequirement(Civilization c, Religion rel) throws CivException {
        if (!meets(c, rel.requirement())) {
            throw new CivException("religion.error.requirement", Messages.arg("religion", rel.name()),
                    Messages.arg("requirement", requirementText(c, rel.requirement())));
        }
    }

    Component requirementText(Civilization c, Rule r) {
        return switch (r.type()) {
            case "nation" -> civ.messages().component("religion.req.nation", Messages.arg("nation", String.valueOf(r.nation())));
            case "structures" -> civ.messages().component("religion.req.structures",
                    Messages.arg("structure", civ.messages().has("religion.structure." + r.structure())
                            ? civ.messages().raw("religion.structure." + r.structure()) : String.valueOf(r.structure())),
                    Messages.number("amount", r.amount()), Messages.number("current", requirementValue(c, r)));
            case "none" -> Component.empty();
            default -> civ.messages().component("religion.req." + r.type(), Messages.number("amount", r.amount()),
                    Messages.number("current", requirementValue(c, r)));
        };
    }

    // --- adoption and change ----------------------------------------------------------------------

    private void found(Player p, String input) throws CivException {
        String[] parts = input.trim().split("\\s+");
        boolean confirmed = parts.length > 1 && parts[parts.length - 1].equalsIgnoreCase("yes");
        String name = confirmed ? input.trim().substring(0, input.trim().length() - 3).trim() : input.trim();
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RELIGION_FOUND);
        Religion rel = find(name);
        if (rel == null) throw new CivException("religion.error.unknown", Messages.arg("name", name));
        if (c.isProvince()) throw new CivException("religion.error.province");
        ReligionState st = state(c);
        if (st.religion() != null) throw new CivException("religion.error.already-has", Messages.arg("religion", religionName(st.religion())));
        if (st.pendingReligion() != null) throw new CivException("religion.error.changing");
        checkRequirement(c, rel);
        double cost = foundingCost(c);
        if (st.points() < cost) {
            throw new CivException("religion.error.points", Messages.number("cost", cost), Messages.number("points", st.points()));
        }
        if (!confirmed) {
            civ.messages().send(p, "religion.found-confirm", Messages.arg("religion", rel.name()),
                    Messages.number("cost", cost), Messages.arg("name", rel.name()));
            return;
        }
        st.points(st.points() - cost);
        adopt(c, rel, ReligionChangedEvent.Reason.ADOPTED, null);
        CivPerms.broadcast(civ, "religion.adopted", Messages.arg("civ", c.name()), Messages.arg("religion", rel.name()));
    }

    private void change(Player p, String input) throws CivException {
        String[] parts = input.trim().split("\\s+");
        boolean confirmed = parts.length > 1 && parts[parts.length - 1].equalsIgnoreCase("yes");
        String name = confirmed ? input.trim().substring(0, input.trim().length() - 3).trim() : input.trim();
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RELIGION_FOUND);
        Religion rel = find(name);
        if (rel == null) throw new CivException("religion.error.unknown", Messages.arg("name", name));
        ReligionState st = state(c);
        if (st.religion() == null) throw new CivException("religion.error.no-religion-to-change");
        if (rel.id().equals(st.religion())) throw new CivException("religion.error.same");
        if (st.pendingReligion() != null) throw new CivException("religion.error.changing");
        checkRequirement(c, rel);
        double cost = foundingCost(c);
        if (st.points() < cost) {
            throw new CivException("religion.error.points", Messages.number("cost", cost), Messages.number("points", st.points()));
        }
        Duration time = changeDuration(c);
        if (!confirmed) {
            civ.messages().send(p, "religion.change-confirm", Messages.arg("religion", rel.name()),
                    Messages.number("cost", cost), Messages.arg("time", Durations.format(time)),
                    Messages.arg("name", rel.name()));
            return;
        }
        st.points(st.points() - cost);
        st.pending(rel.id(), Instant.now().plus(time));
        save(st);
        CivPerms.tellCiv(civ, c, "religion.change-started", Messages.arg("religion", rel.name()),
                Messages.arg("time", Durations.format(time)));
    }

    Duration changeDuration(Civilization c) {
        ConfigurationSection ch = cfg().getConfigurationSection("change");
        double perTown = ch == null ? 1 : ch.getDouble("hours-per-town", 1);
        double max = ch == null ? 24 : ch.getDouble("max-hours", 24);
        double min = ch == null ? 1 : ch.getDouble("min-hours", 1);
        double hours = Math.max(min, Math.min(max, civ.state().towns(c).size() * perTown));
        if (wonders.owns(c, "notre_dame")) hours *= ch == null ? 0.5 : ch.getDouble("notre-dame-multiplier", 0.5);
        return Duration.ofSeconds((long) (hours * 3600));
    }

    private void adopt(Civilization c, Religion rel, ReligionChangedEvent.Reason reason, String old) {
        ReligionState st = state(c);
        if (old != null) {
            rating(old).order().remove(c.id());
            saveRating(old);
        }
        st.religion(rel.id());
        st.pending(null, null);
        st.adoptionCounts().clear();
        if ("structures_below_adoption".equals(rel.loss().type()) && rel.loss().structure() != null) {
            st.adoptionCounts().put(rel.loss().structure(), uncapturedStructures(c, rel.loss().structure()));
        }
        if (reason == ReligionChangedEvent.Reason.CHANGED) {
            st.noPointsUntil(Instant.now().plus(Duration.ofHours(cfg().getLong("no-points-after-change-hours", 24))));
        }
        List<String> order = rating(rel.id()).order();
        if (!order.contains(c.id())) order.add(c.id());
        saveRating(rel.id());
        save(st);
        new ReligionChangedEvent(c.id(), old, rel.id(), reason).call();
        civ.stats().invalidate();
    }

    /** Loses the religion (war, capture, conditions); points are then blocked for a day. */
    public void lose(Civilization c, String reasonKey) {
        ReligionState st = state(c);
        String old = st.religion();
        if (old == null && st.pendingReligion() == null) return;
        if (old != null) {
            rating(old).order().remove(c.id());
            saveRating(old);
        }
        st.religion(null);
        st.pending(null, null);
        st.adoptionCounts().clear();
        st.noPointsUntil(Instant.now().plus(Duration.ofHours(cfg().getLong("no-points-after-loss-hours", 24))));
        save(st);
        new ReligionChangedEvent(c.id(), old, null, ReligionChangedEvent.Reason.LOST).call();
        civ.stats().invalidate();
        CivPerms.tellCiv(civ, c, "religion.lost", Messages.arg("religion", religionName(old)),
                Messages.arg("reason", civ.messages().component("religion.loss." + reasonKey)));
    }

    // --- clock ------------------------------------------------------------------------------------

    private double faith(Town t) {
        TownApi towns = civ.apiOrNull(TownApi.class);
        if (towns != null) {
            double v = towns.faithPerHour(t);
            return Double.isFinite(v) && v > 0 ? v : 0;
        }
        double v = civ.stats().town(t, Stats.FAITH, 0);
        if (t.status() == TownStatus.CAPTURED) v *= 0.5;
        return Math.max(0, v);
    }

    private void tick() {
        boolean warNow = war.isWarTime();
        if (warNow && !meta.war()) {
            for (Civilization c : civ.state().civs()) snapshot(c);
        }
        boolean warEnded = !warNow && meta.war();
        if (warNow != meta.war()) {
            meta.war(warNow);
            civ.saves().save(META_COLLECTION, meta);
        }
        Instant now = Instant.now();
        for (Civilization c : List.copyOf(civ.state().civs())) {
            ReligionState st = state(c);
            double income = 0;
            for (Town t : civ.state().towns(c)) income += faith(t);
            st.hourlyIncome(income);
            if (!st.pointsBlocked()) st.points(st.points() + income / 60.0);
            // Capture: the civilization loses its religion (and a running change).
            boolean conquered = c.isConquered();
            if (conquered && !st.wasConquered()) {
                st.wasConquered(true);
                lose(c, "captured");
            } else if (!conquered && st.wasConquered()) {
                st.wasConquered(false);
            }
            Religion rel = st.religion() == null ? null : religions.get(st.religion());
            if (rel != null && "capital_captured".equals(rel.loss().type())) {
                Town capital = civ.state().capital(c);
                if (conquered || capital == null || capital.status() == TownStatus.CAPTURED) lose(c, "capital");
            }
            if (st.pendingReligion() != null && st.changeEnds() != null && !now.isBefore(st.changeEnds())) {
                Religion target = religions.get(st.pendingReligion());
                if (target != null) {
                    String old = st.religion();
                    adopt(c, target, ReligionChangedEvent.Reason.CHANGED, old);
                    CivPerms.broadcast(civ, "religion.changed", Messages.arg("civ", c.name()),
                            Messages.arg("religion", target.name()));
                } else {
                    st.pending(null, null);
                }
            }
            save(st);
        }
        if (warEnded) {
            for (Civilization c : List.copyOf(civ.state().civs())) checkWarLoss(c);
            resortAll();
            meta.pendingResort(false);
            civ.saves().save(META_COLLECTION, meta);
        }
    }

    private void daily() {
        for (Civilization c : List.copyOf(civ.state().civs())) {
            ReligionState st = state(c);
            Religion rel = st.religion() == null ? null : religions.get(st.religion());
            if (rel != null && "structures_below_adoption".equals(rel.loss().type())) {
                Integer at = st.adoptionCounts().get(rel.loss().structure());
                if (at != null && uncapturedStructures(c, rel.loss().structure()) < at) lose(c, "structures");
            }
        }
        if (war.isWarTime()) {
            meta.pendingResort(true);
            civ.saves().save(META_COLLECTION, meta);
        } else {
            resortAll();
        }
    }

    private void snapshot(Civilization c) {
        ReligionState st = state(c);
        ReligionState.WarSnapshot s = new ReligionState.WarSnapshot();
        s.science = uncapturedScience(c);
        s.culture = uncapturedCulture(c);
        s.towns = civ.state().towns(c).size();
        s.capitalUnhappiness = capitalUnhappiness(c);
        st.warSnapshot(s);
        save(st);
    }

    private void checkWarLoss(Civilization c) {
        ReligionState st = state(c);
        ReligionState.WarSnapshot s = st.warSnapshot();
        st.warSnapshot(null);
        save(st);
        Religion rel = st.religion() == null ? null : religions.get(st.religion());
        if (rel == null || s == null) return;
        Rule loss = rel.loss();
        boolean lost = switch (loss.type()) {
            case "beakers_drop" -> {
                double bonus = 0;
                for (EffectSpec e : rel.effects()) {
                    if (e.stat().equals(Stats.BEAKERS) && e.op() == Op.ADD) bonus += e.value();
                }
                bonus *= strength(c) * Math.max(1, s.towns);
                yield bonus > 0 && s.science - uncapturedScience(c) >= bonus;
            }
            case "culture_drop" -> s.culture > 0 && uncapturedCulture(c) <= s.culture * (1 - loss.fraction());
            case "towns_lost" -> s.towns > 0 && civ.state().towns(c).size() <= s.towns * (1 - loss.fraction());
            case "capital_unhappiness_rise" -> capitalUnhappiness(c) - s.capitalUnhappiness >= loss.amount();
            case "structures_below_adoption" -> {
                Integer at = st.adoptionCounts().get(loss.structure());
                yield at != null && uncapturedStructures(c, loss.structure()) < at;
            }
            default -> false;
        };
        if (lost) lose(c, "war");
    }

    /** Daily (or post-war) re-sort of every rating by accumulated points. */
    void resortAll() {
        for (ReligionRating r : ratings.values()) {
            r.order().removeIf(id -> civ.state().civ(id) == null);
            r.order().sort(Comparator.comparingDouble((String id) -> -states.getOrDefault(id, new ReligionState(id)).points()));
            civ.saves().save(RATING_COLLECTION, r);
        }
        civ.stats().invalidate();
    }

    // --- prophet ----------------------------------------------------------------------------------

    private void useProphet(Player player, PlayerInteractEvent event) {
        try {
            Civilization c = CivPerms.civOf(civ, player);
            ReligionState st = state(c);
            Instant now = Instant.now();
            if (st.prophetCooldownUntil() != null && st.prophetCooldownUntil().isAfter(now)) {
                throw new CivException("religion.error.prophet-cooldown",
                        Messages.arg("time", Durations.format(Duration.between(now, st.prophetCooldownUntil()))));
            }
            ItemStack hand = event.getItem();
            if (hand == null) return;
            ConfigurationSection prophet = civ.balance().section("religion", "prophet");
            double points = prophet.getDouble("points", 300);
            hand.setAmount(hand.getAmount() - 1);
            st.points(st.points() + points);
            st.prophetCooldownUntil(now.plus(Duration.ofMinutes(prophet.getLong("cooldown-minutes", 60))));
            save(st);
            CivPerms.tellCiv(civ, c, "religion.prophet.used", Messages.arg("player", player.getName()),
                    Messages.number("points", points));
        } catch (CivException e) {
            civ.messages().send(player, e.key(), e.args());
        }
    }

    // --- effects ----------------------------------------------------------------------------------

    @Override
    public void contribute(EffectSink sink) {
        ConfigurationSection mods = civ.balance().section("religion", "modifiers");
        for (Civilization c : civ.state().civs()) {
            if (wonders.owns(c, "parthenon")) {
                sink.civ(c, new Modifier(Stats.FAITH, Op.PERCENT, mods.getDouble("parthenon-percent", 0.5), Scope.CIV,
                        "wonder:parthenon"));
            }
            String nd = mods.getString("notre-dame-nation", "french");
            if (nd.equalsIgnoreCase(c.nation()) && wonders.owns(c, "notre_dame")) {
                sink.civ(c, new Modifier(Stats.FAITH, Op.PERCENT, mods.getDouble("notre-dame-nation-percent", 0.1),
                        Scope.CIV, "wonder:notre_dame"));
            }
            ReligionState st = states.get(c.id());
            if (st == null || st.religion() == null) continue;
            Religion rel = religions.get(st.religion());
            if (rel == null) continue;
            double strength = strength(c);
            if (strength <= 0) continue;
            for (EffectSpec e : rel.effects()) {
                e.apply(sink, c, null, e.binary() ? 1 : strength, "religion:" + rel.id());
            }
        }
    }

    // --- commands ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> command() {
        return Cmd.literal("religion")
                .executes(Cmd.player((p, ctx) -> info(p)))
                .then(Cmd.literal("info").executes(Cmd.player((p, ctx) -> info(p))))
                .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> list(p))))
                .then(Cmd.literal("rating")
                        .executes(Cmd.player((p, ctx) -> rating(p, null)))
                        .then(Cmd.arg("religion", StringArgumentType.greedyString()).suggests(religionSuggestions())
                                .executes(Cmd.player((p, ctx) -> rating(p, StringArgumentType.getString(ctx, "religion"))))))
                .then(Cmd.literal("change").then(Cmd.arg("args", StringArgumentType.greedyString())
                        .suggests(religionSuggestions())
                        .executes(Cmd.player((p, ctx) -> change(p, StringArgumentType.getString(ctx, "args"))))))
                .then(Cmd.literal("admin").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.literal("points").then(Cmd.arg("civ", StringArgumentType.word()).suggests(civSuggestions())
                                .then(Cmd.arg("amount", DoubleArgumentType.doubleArg(-1e9, 1e9))
                                        .executes(Cmd.run(ctx -> adminPoints(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "civ"),
                                                DoubleArgumentType.getDouble(ctx, "amount")))))))
                        .then(Cmd.literal("set").then(Cmd.arg("civ", StringArgumentType.word()).suggests(civSuggestions())
                                .then(Cmd.arg("religion", StringArgumentType.greedyString()).suggests(religionSuggestions())
                                        .executes(Cmd.run(ctx -> adminSet(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "civ"),
                                                StringArgumentType.getString(ctx, "religion"))))))))
                .then(Cmd.arg("args", StringArgumentType.greedyString()).suggests(religionSuggestions())
                        .executes(Cmd.player((p, ctx) -> found(p, StringArgumentType.getString(ctx, "args")))));
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> religionSuggestions() {
        return Cmd.suggest(() -> religions.values().stream().map(Religion::name).toList());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> civSuggestions() {
        return Cmd.suggest(() -> civ.state().civs().stream().map(Civilization::name).toList());
    }

    private void info(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        ReligionState st = state(c);
        civ.messages().send(p, "religion.info-header", Messages.arg("civ", c.name()),
                Messages.arg("religion", religionName(st.religion())));
        if (st.religion() != null) {
            civ.messages().sendRaw(p, "religion.info-place", Messages.arg("place", place(c)),
                    Messages.arg("strength", Format.percent(strength(c))));
        }
        civ.messages().sendRaw(p, "religion.info-income", Messages.number("income", st.hourlyIncome()));
        if (c.isLeader(p.getUniqueId()) || p.hasPermission("civcraft.admin")) {
            civ.messages().sendRaw(p, "religion.info-points", Messages.number("points", st.points()),
                    Messages.number("cost", foundingCost(c)));
        }
        if (st.pendingReligion() != null && st.changeEnds() != null) {
            civ.messages().sendRaw(p, "religion.info-pending", Messages.arg("religion", religionName(st.pendingReligion())),
                    Messages.arg("time", Durations.format(Duration.between(Instant.now(), st.changeEnds()))));
        }
        if (st.pointsBlocked()) {
            civ.messages().sendRaw(p, "religion.info-blocked",
                    Messages.arg("time", Durations.format(Duration.between(Instant.now(), st.noPointsUntil()))));
        }
    }

    private void list(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        civ.messages().send(p, "religion.list-header", Messages.number("cost", foundingCost(c)));
        for (Religion r : religions.values()) {
            boolean ok = meets(c, r.requirement());
            civ.messages().sendRaw(p, "religion.list-line", Messages.arg("religion", r.name()),
                    Messages.arg("followers", rating(r.id()).order().size()),
                    Messages.arg("effect", String.join("; ", r.description())),
                    Messages.arg("requirement", requirementText(c, r.requirement())),
                    Messages.arg("mark", civ.messages().component(ok ? "religion.mark-ok" : "religion.mark-no")));
        }
    }

    private void rating(Player p, String input) throws CivException {
        Civilization own = civ.state().civOf(p);
        Religion rel = input == null ? (own == null ? null : religions.get(religion(own))) : find(input);
        if (rel == null) throw new CivException("religion.error.unknown", Messages.arg("name", input == null ? "" : input));
        boolean admin = p.hasPermission("civcraft.admin");
        civ.messages().send(p, "religion.rating-header", Messages.arg("religion", rel.name()));
        int place = 1;
        for (String id : rating(rel.id()).order()) {
            Civilization c = civ.state().civ(id);
            if (c == null) continue;
            boolean visible = admin || (own != null && own.id().equals(id));
            civ.messages().sendRaw(p, visible ? "religion.rating-line" : "religion.rating-hidden",
                    Messages.arg("place", place), Messages.arg("civ", c.name()),
                    Messages.arg("strength", Format.percent(strengthForPlace(place))),
                    Messages.number("points", state(c).points()));
            place++;
        }
    }

    private void adminPoints(CommandSender sender, String civName, double amount) throws CivException {
        Civilization c = civ.state().civByName(civName);
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", civName));
        ReligionState st = state(c);
        st.points(st.points() + amount);
        save(st);
        civ.messages().send(sender, "religion.admin-points", Messages.arg("civ", c.name()), Messages.number("points", st.points()));
    }

    private void adminSet(CommandSender sender, String civName, String religionName) throws CivException {
        Civilization c = civ.state().civByName(civName);
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", civName));
        if (religionName.equalsIgnoreCase("none")) {
            lose(c, "admin");
        } else {
            Religion rel = find(religionName);
            if (rel == null) throw new CivException("religion.error.unknown", Messages.arg("name", religionName));
            adopt(c, rel, ReligionChangedEvent.Reason.ADMIN, state(c).religion());
        }
        civ.messages().send(sender, "religion.admin-set", Messages.arg("civ", c.name()),
                Messages.arg("religion", religionName(state(c).religion())));
    }
}
