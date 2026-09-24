package com.civcraft.government;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.civ.CivPerms;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.Durations;
import com.civcraft.economy.EconomyEngine;
import com.civcraft.economy.EconomyMath;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.Stats;
import com.civcraft.event.GovernmentChangedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.science.ResearchApi;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Governments and nations (spec §13, §14): the multiplier table as effects, special effects, the
 * anarchy transition, and the one-time nation choice with all national bonuses.
 */
public final class GovernmentModule implements Module {

    /** Stats read from civ sheets: set by wonders (Notre-Dame) and similar sources. */
    public static final String NO_ANARCHY = "no_anarchy";
    public static final String GOVERNMENT_CHANGE_TIME = "government_change_time";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private CivCraft civ;
    private final Map<String, GovernmentDef> governments = new LinkedHashMap<>();
    private final Map<String, NationDef> nations = new LinkedHashMap<>();
    private boolean warnedNoResearch;

    @Override
    public String id() {
        return "government";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("government");
        ConfigurationSection g = civ.balance().section("government", "governments");
        for (String id : g.getKeys(false)) {
            ConfigurationSection s = g.getConfigurationSection(id);
            if (s != null) governments.put(id, GovernmentDef.read(id, s));
        }
        ConfigurationSection n = civ.balance().section("government", "nations");
        for (String id : n.getKeys(false)) {
            ConfigurationSection s = n.getConfigurationSection(id);
            if (s != null) nations.put(id, NationDef.read(id, s));
        }
        if (!governments.containsKey(startId()) || !governments.containsKey(anarchyId())) {
            throw new IllegalStateException("government.yml must define the start and anarchy governments");
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.stats().register(this::contributeGovernment);
        civ.stats().register(this::contributeNations);
        civ.clock().everyMinute("government-transitions", this::tickTransitions);
        // Repair civilizations saved with an unknown government (e.g. removed from the config).
        for (Civilization c : civ.state().civs()) {
            if (!governments.containsKey(c.government())) {
                civ.logger().warning("Civilization " + c.name() + " had unknown government " + c.government() + "; reset");
                c.government(startId());
                civ.state().save(c);
            }
        }
    }

    public String startId() {
        return civ.balance().file("government").getString("start", "tribalism");
    }

    public String anarchyId() {
        return civ.balance().file("government").getString("anarchy", "anarchy");
    }

    public Collection<GovernmentDef> governments() {
        return governments.values();
    }

    public Collection<NationDef> nations() {
        return nations.values();
    }

    public GovernmentDef current(Civilization c) {
        GovernmentDef d = governments.get(c.government());
        return d != null ? d : governments.get(startId());
    }

    public NationDef nation(Civilization c) {
        return c.nation() == null ? null : nations.get(c.nation());
    }

    public boolean isAnarchy(Civilization c) {
        return anarchyId().equals(c.government());
    }

    /** Maximum income tax: government limit plus MAX_TAX modifiers (greed, capped). */
    public double maxTax(Civilization c) {
        double extra = Math.min(civ.balance().getDouble("government", "max-tax-bonus-cap", 0.05),
                Math.max(0, civ.stats().civ(c).get(Stats.MAX_TAX)));
        return current(c).maxTax() + extra;
    }

    /** The tax rate actually applied: the stored rate, clamped to the current maximum (audit A-H11). */
    public double effectiveTaxes(Civilization c) {
        return Math.min(EconomyMath.clamp01(c.taxes()), maxTax(c));
    }

    /** Coins per beaker (spec §8.2): government price plus BEAKER_PRICE modifiers; talents ignored in mercantilism. */
    public double beakerPrice(Civilization c) {
        GovernmentDef g = current(c);
        double price = g.beakerPrice();
        for (Modifier m : civ.stats().civ(c).breakdown(Stats.BEAKER_PRICE)) {
            if (!g.talentBeakerDiscount() && m.source().startsWith("talent:")) continue;
            if (m.op() == Op.ADD) price += m.value();
        }
        return Math.max(civ.balance().getDouble("government", "min-beaker-price", 1), price);
    }

    // --- availability ---------------------------------------------------------------------------

    public boolean available(Civilization c, GovernmentDef g) {
        if (!g.selectable()) return false;
        if (g.tech() == null || g.tech().isBlank()) return true;
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        if (research == null) {
            if (!warnedNoResearch) {
                warnedNoResearch = true;
                civ.logger().warning("No research module: governments that need a technology are unavailable");
            }
            return false;
        }
        return research.hasTech(c, g.tech()) && research.unlocked(c, "governments", g.id());
    }

    // --- transitions ----------------------------------------------------------------------------

    /** Hours of anarchy/transition for this civilization and target (spec §13.4). */
    public Duration transitionTime(Civilization c, GovernmentDef target) {
        int players = 0;
        for (Town t : civ.state().towns(c)) if (t.status() != TownStatus.CAPTURED) players += t.residents().size();
        ConfigurationSection s = civ.balance().section("government", "transition");
        double multiplier = 1;
        NationDef nation = nation(c);
        if (nation != null) {
            if (target.id().equals(nation.government())) multiplier *= s.getDouble("national-government-multiplier", 0.75);
            multiplier *= nation.governmentChangeMultiplier();
        }
        multiplier *= civ.stats().civ(c).apply(GOVERNMENT_CHANGE_TIME, 1.0);
        double hours = EconomyMath.transitionHours(players, s.getDouble("min-hours", 1), s.getDouble("max-hours", 24), multiplier);
        return Duration.ofSeconds(Math.round(hours * 3600));
    }

    /** Starts a government change (spec §13.4). Anarchy applies unless a NO_ANARCHY effect is active. */
    public void startChange(Civilization c, GovernmentDef target, boolean forceAnarchy) {
        Duration time = transitionTime(c, target);
        String old = c.government();
        boolean anarchy = forceAnarchy || civ.stats().civ(c).get(NO_ANARCHY) <= 0;
        c.startGovernmentChange(target.id(), Instant.now().plus(time));
        if (anarchy) c.government(anarchyId());
        civ.state().save(c);
        civ.stats().invalidate();
        civ.module(TownModule.class).production().invalidate();
        Channels.civ(c, anarchy ? "government.change.anarchy" : "government.change.no-anarchy",
                Messages.arg("gov", target.name()), Messages.arg("time", Durations.format(time)));
        if (!old.equals(c.government())) new GovernmentChangedEvent(c.id(), old, c.government()).call();
    }

    private void tickTransitions() {
        Instant now = Instant.now();
        for (Civilization c : List.copyOf(civ.state().civs())) {
            if (c.governmentChangeEnds() == null || c.governmentChangeEnds().isAfter(now)) continue;
            String old = c.government();
            c.finishGovernmentChange();
            if (!governments.containsKey(c.government())) c.government(startId());
            civ.state().save(c);
            civ.stats().invalidate();
            civ.module(TownModule.class).production().invalidate();
            Channels.civ(c, "government.change.done", Messages.arg("gov", current(c).name()));
            if (!old.equals(c.government())) new GovernmentChangedEvent(c.id(), old, c.government()).call();
        }
    }

    // --- effects --------------------------------------------------------------------------------

    private void contributeGovernment(EffectSink sink) {
        for (Civilization c : civ.state().civs()) {
            GovernmentDef g = current(c);
            String src = "government:" + g.id();
            mult(sink, c, Stats.HAMMERS, g.hammers(), src);
            mult(sink, c, Stats.BEAKERS, g.science(), src);
            mult(sink, c, Stats.CULTURE, g.culture(), src);
            mult(sink, c, Stats.GROWTH, g.growth(), src);
            mult(sink, c, Stats.UPKEEP, g.upkeep(), src);
            mult(sink, c, Stats.COTTAGE, g.cottage(), src);
            mult(sink, c, Stats.TRADE_SHIP, g.tradeShip(), src);
            mult(sink, c, "trade", g.trade(), src);
            mult(sink, c, "strategic", g.strategic(), src);
            mult(sink, c, Stats.INCOME, g.income(), src);
            for (Modifier m : g.effects()) sink.civ(c, m);
        }
    }

    private static void mult(EffectSink sink, Civilization c, String stat, double value, String source) {
        if (value == 1.0) return;
        sink.civ(c, new Modifier(stat, Op.MULTIPLY, value, Scope.CIV, source));
    }

    private void contributeNations(EffectSink sink) {
        TownModule towns = civ.module(TownModule.class);
        EconomyEngine economy = towns.economy();
        StructureApi structures = civ.apiOrNull(StructureApi.class);
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        for (Civilization c : civ.state().civs()) {
            NationDef n = nation(c);
            if (n == null) continue;
            for (Modifier m : n.effects()) sink.civ(c, m);
            int era = research == null ? 0 : research.era(c);
            Town capital = civ.state().capital(c);
            if (capital != null) for (Modifier m : n.capitalEffects()) sink.town(capital, m.withSource("nation:" + n.id()));
            for (Town t : civ.state().towns(c)) {
                if (t.status() == TownStatus.NATIVE) for (Modifier m : n.nativeTownEffects()) sink.town(t, m);
                for (NationDef.ClassBonus b : n.perChunk()) {
                    int chunks = economy.chunksOfClass(t, b.chunkClass());
                    if (chunks > 0) sink.town(t, b.modifier().scaled(chunks));
                }
                for (NationDef.ClassBonus b : n.townWithChunkClass()) {
                    if (economy.chunksOfClass(t, b.chunkClass()) > 0) sink.civ(c, b.modifier());
                }
                if (structures != null) {
                    for (NationDef.StructureBonus b : n.perStructure()) {
                        int count = complete(structures, t, b.types());
                        if (count > 0) sink.town(t, b.modifier().scaled(count));
                    }
                    for (NationDef.StructureBonus b : n.perStructureEra()) {
                        int count = complete(structures, t, b.types());
                        if (count > 0 && era > 0) sink.town(t, b.modifier().scaled((double) count * era));
                    }
                }
            }
        }
    }

    private static int complete(StructureApi api, Town t, List<String> types) {
        int n = 0;
        for (String type : types) for (StructureApi.Placed p : api.of(t, type)) if (p.complete()) n++;
        return n;
    }

    // --- commands (/civ gov, /civ nation) -------------------------------------------------------

    public LiteralArgumentBuilder<CommandSourceStack> govCommand() {
        return lit("gov")
                .executes(Cmd.player((p, ctx) -> info(p)))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p))))
                .then(lit("list").executes(Cmd.player((p, ctx) -> list(p))))
                .then(lit("remain").executes(Cmd.player((p, ctx) -> remain(p))))
                .then(lit("change").then(CmdKit.text("government")
                        .suggests(CmdKit.values(() -> governments.values().stream().filter(GovernmentDef::selectable).map(GovernmentDef::id).toList()))
                        .executes(Cmd.player((p, ctx) -> change(p, arg(ctx, "government"))))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> nationCommand() {
        return lit("nation")
                .executes(Cmd.player((p, ctx) -> nationInfo(p)))
                .then(CmdKit.text("nation").suggests(CmdKit.values(nations.keySet()))
                        .executes(Cmd.player((p, ctx) -> chooseNation(p, arg(ctx, "nation")))));
    }

    private Civilization myCiv(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        return c;
    }

    private void info(Player p) throws CivException {
        Civilization c = myCiv(p);
        GovernmentDef g = current(c);
        Messages m = civ.messages();
        m.sendRaw(p, "government.info.header", Messages.arg("gov", g.name()));
        m.sendRaw(p, "government.info.table",
                Messages.arg("trade", Format.percent(g.trade())), Messages.arg("strategic", Format.percent(g.strategic())),
                Messages.arg("cottage", Format.percent(g.cottage())), Messages.arg("upkeep", Format.percent(g.upkeep())),
                Messages.arg("growth", Format.percent(g.growth())), Messages.arg("maxtax", Format.percent(maxTax(c))),
                Messages.arg("culture", Format.percent(g.culture())), Messages.arg("hammers", Format.percent(g.hammers())),
                Messages.arg("science", Format.percent(g.science())), Messages.arg("tradeship", Format.percent(g.tradeShip())),
                Messages.number("beaker", beakerPrice(c)));
        for (String line : g.description()) p.sendMessage(m.parse(line));
        if (c.targetGovernment() != null) remain(p);
    }

    private void list(Player p) throws CivException {
        Civilization c = myCiv(p);
        Messages m = civ.messages();
        m.sendRaw(p, "government.list.header");
        for (GovernmentDef g : governments.values()) {
            if (!g.selectable()) continue;
            boolean avail = available(c, g);
            if (!avail && !civ.balance().file("government").getBoolean("list-unavailable", false)) continue;
            String key = g.id().equals(c.government()) ? "government.list.current" : avail ? "government.list.available" : "government.list.locked";
            m.sendRaw(p, key, Messages.arg("gov", g.name()), Messages.arg("id", g.id()));
        }
    }

    private void remain(Player p) throws CivException {
        Civilization c = myCiv(p);
        if (c.targetGovernment() == null || c.governmentChangeEnds() == null) throw new CivException("government.remain.none");
        GovernmentDef target = governments.get(c.targetGovernment());
        Duration left = Duration.between(Instant.now(), c.governmentChangeEnds());
        civ.messages().send(p, "government.remain.info",
                Messages.arg("gov", target == null ? c.targetGovernment() : target.name()),
                Messages.arg("time", Durations.format(left)),
                Messages.arg("end", TIME.format(c.governmentChangeEnds().atZone(civ.clock().zone()))));
    }

    private void change(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "gov");
        GovernmentDef target = Lookup.option(input, governments.values(), GovernmentDef::id, GovernmentDef::name);
        if (target == null || !target.selectable()) throw new CivException("government.unknown", Messages.arg("name", input));
        if (c.targetGovernment() != null) throw new CivException("government.change.in-progress");
        if (target.id().equals(c.government())) throw new CivException("government.change.same");
        if (!available(c, target)) throw new CivException("government.change.locked", Messages.arg("gov", target.name()));
        Duration time = transitionTime(c, target);
        Messages m = civ.messages();
        boolean anarchy = civ.stats().civ(c).get(NO_ANARCHY) <= 0;
        Prompts.confirm(p, m.component("government.change.confirm-title", Messages.arg("gov", target.name())),
                m.lines(anarchy ? "government.change.confirm-anarchy" : "government.change.confirm-no-anarchy",
                        Messages.arg("gov", target.name()), Messages.arg("time", Durations.format(time))),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    Civilization now = civ.state().civOf(player);
                    if (now == null || !now.id().equals(c.id()) || c.targetGovernment() != null
                            || !CivPerms.has(c, player.getUniqueId(), "gov") || !available(c, target)) {
                        m.send(player, "government.change.stale");
                        return;
                    }
                    startChange(c, target, false);
                });
    }

    private void nationInfo(Player p) {
        Messages m = civ.messages();
        m.sendRaw(p, "government.nation.header");
        for (NationDef n : nations.values()) {
            GovernmentDef g = governments.get(n.government());
            m.sendRaw(p, "government.nation.entry", Messages.arg("nation", n.name()), Messages.arg("id", n.id()),
                    Messages.arg("gov", g == null ? "-" : g.name()));
            for (String line : n.description()) p.sendMessage(m.parse(line));
        }
        Civilization c = civ.state().civOf(p);
        if (c != null && nation(c) != null) m.send(p, "government.nation.current", Messages.arg("nation", nation(c).name()));
    }

    private void chooseNation(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        if (c.rank(p.getUniqueId()) != Civilization.Rank.OWNER) throw new CivException("civ.owner-only");
        if (c.isProvince()) throw new CivException("government.nation.province");
        if (c.nation() != null) throw new CivException("government.nation.already");
        NationDef n = Lookup.option(input, nations.values(), NationDef::id, NationDef::name);
        if (n == null) throw new CivException("government.nation.unknown", Messages.arg("name", input));
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("government.nation.confirm-title", Messages.arg("nation", n.name())),
                m.lines("government.nation.confirm-body", Messages.arg("nation", n.name())),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    if (c.nation() != null || c.rank(player.getUniqueId()) != Civilization.Rank.OWNER) return;
                    c.nation(n.id());
                    civ.state().save(c);
                    civ.stats().invalidate();
                    civ.module(TownModule.class).production().invalidate();
                    Channels.global("government.nation.chosen", Messages.arg("civ", c.name()), Messages.arg("nation", n.name()));
                });
    }

    /** Lists government names for other modules' GUIs. */
    public List<String> governmentNames() {
        List<String> names = new ArrayList<>();
        for (GovernmentDef g : governments.values()) names.add(g.name());
        return names;
    }
}
