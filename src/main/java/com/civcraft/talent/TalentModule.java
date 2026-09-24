package com.civcraft.talent;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Scope;
import com.civcraft.event.TalentChosenEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.science.CivCommandGraft;
import com.civcraft.science.CivPerms;
import com.civcraft.science.EffectSpec;
import com.civcraft.science.WonderIndex;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Civilization talents (spec 01 §15): one of three talents per capital culture level 1..10, lost when the
 * capital levels up without a choice. All bonuses are stat modifiers contributed by this module.
 */
public final class TalentModule implements Module, TalentApi, EffectProvider {

    public static final String COLLECTION = "talents";

    record Talent(int level, int choice, String name, List<String> description, List<EffectSpec> effects) {
    }

    record Level(int level, String theme, Material icon, Map<Integer, Talent> choices) {
    }

    private CivCraft civ;
    private WonderIndex wonders;
    private final Map<Integer, Level> levels = new LinkedHashMap<>();
    private final Map<String, TalentState> states = new HashMap<>();
    private int maxLevel;

    @Override
    public String id() {
        return "talent";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("talent");
        maxLevel = civ.balance().getInt("talents", "max-level", 10);
        ConfigurationSection ls = civ.balance().section("talents", "levels");
        for (String key : ls.getKeys(false)) {
            ConfigurationSection s = ls.getConfigurationSection(key);
            if (s == null) continue;
            int level = Integer.parseInt(key);
            Map<Integer, Talent> choices = new LinkedHashMap<>();
            ConfigurationSection cs = s.getConfigurationSection("choices");
            if (cs != null) {
                for (String ck : cs.getKeys(false)) {
                    ConfigurationSection c = cs.getConfigurationSection(ck);
                    if (c == null) continue;
                    int choice = Integer.parseInt(ck);
                    choices.put(choice, new Talent(level, choice, c.getString("name", level + "." + choice),
                            c.getStringList("description"), EffectSpec.parse(c.getList("effects"), Scope.CIV)));
                }
            }
            Material icon = Material.matchMaterial(s.getString("icon", "BOOK"));
            levels.put(level, new Level(level, s.getString("theme", key), icon == null ? Material.BOOK : icon, choices));
        }
        civ.store().createCollection(COLLECTION);
        for (TalentState st : civ.store().loadAll(COLLECTION, TalentState.class)) {
            if (st.civId() == null || civ.state().civ(st.civId()) == null) {
                if (st.civId() != null) civ.saves().delete(COLLECTION, st.civId());
                continue;
            }
            st.repair();
            states.put(st.civId(), st);
        }
    }

    @Override
    public void enable(CivCraft civ) {
        wonders = new WonderIndex(civ);
        civ.stats().register(this);
        civ.clock().everyMinute("talents", this::refreshAll);
        PlayerStatApplier applier = new PlayerStatApplier(civ);
        civ.listen(applier);
        int seconds = Math.max(1, civ.balance().getInt("talents", "player-stats.refresh-seconds", 5));
        civ.tasks().timer(20L * seconds, 20L * seconds, applier::refreshAll);
        CivCommandGraft.add(civ, this::command);
    }

    // --- state ------------------------------------------------------------------------------------

    TalentState state(Civilization c) {
        return states.computeIfAbsent(c.id(), id -> {
            TalentState st = new TalentState(id);
            civ.saves().save(COLLECTION, st);
            return st;
        });
    }

    Level level(int level) {
        return levels.get(level);
    }

    Map<Integer, Level> levels() {
        return levels;
    }

    int maxLevel() {
        return maxLevel;
    }

    CivCraft civ() {
        return civ;
    }

    /** Current capital culture level capped at the last talent level. */
    int capitalLevel(Civilization c) {
        Town capital = civ.state().capital(c);
        return capital == null ? 0 : Math.min(maxLevel, civ.culture().level(capital));
    }

    private void refreshAll() {
        for (Civilization c : List.copyOf(civ.state().civs())) refresh(c);
    }

    /** Tracks the capital level; notifies leaders when a new talent can be chosen. */
    void refresh(Civilization c) {
        int lvl = capitalLevel(c);
        TalentState st = state(c);
        if (lvl <= st.maxLevelReached()) return;
        int previous = st.maxLevelReached();
        st.maxLevelReached(lvl);
        civ.saves().save(COLLECTION, st);
        for (int l = Math.max(1, previous); l < lvl; l++) {
            if (!st.choices().containsKey(l) && levels.containsKey(l) && previous > 0) {
                CivPerms.tellLeaders(civ, c, "talent.lost", Messages.arg("level", l));
            }
        }
        if (levels.containsKey(lvl) && !st.choices().containsKey(lvl)) {
            CivPerms.tellLeaders(civ, c, "talent.available", Messages.arg("level", lvl),
                    Messages.arg("theme", levels.get(lvl).theme()));
        }
    }

    // --- TalentApi --------------------------------------------------------------------------------

    @Override
    public int choice(Civilization c, int level) {
        return state(c).choices().getOrDefault(level, 0);
    }

    @Override
    public Map<Integer, Integer> choices(Civilization c) {
        return Map.copyOf(state(c).choices());
    }

    @Override
    public int chosenCount(Civilization c) {
        return state(c).choices().size();
    }

    @Override
    public int availableLevel(Civilization c) {
        refresh(c);
        TalentState st = state(c);
        int lvl = st.maxLevelReached();
        if (lvl < 1 || lvl > maxLevel || !levels.containsKey(lvl) || st.choices().containsKey(lvl)) return 0;
        return lvl;
    }

    // --- choosing ---------------------------------------------------------------------------------

    /** Validates and returns the talent the player may pick now. */
    Talent pending(Player player, Civilization c, int choice) throws CivException {
        CivPerms.check(civ, player, c, CivPerms.TALENT);
        int lvl = availableLevel(c);
        if (lvl == 0) throw new CivException("talent.error.none-available");
        Talent t = levels.get(lvl).choices().get(choice);
        if (t == null) throw new CivException("talent.error.bad-choice");
        return t;
    }

    /** Asks for confirmation (spec: the choice is confirmed) and applies it. */
    void confirmChoice(Player player, int choice) throws CivException {
        Civilization c = CivPerms.civOf(civ, player);
        Talent t = pending(player, c, choice);
        List<Component> body = new ArrayList<>();
        body.add(civ.messages().component("talent.confirm-body", Messages.arg("level", t.level()),
                Messages.arg("name", t.name())));
        for (String line : t.description()) body.add(civ.messages().parse("<gray>" + line));
        body.add(civ.messages().component("talent.confirm-warning"));
        Prompts.confirm(player, civ.messages().component("talent.confirm-title"), body,
                civ.messages().component("talent.yes"), civ.messages().component("talent.no"), p -> {
                    try {
                        Civilization pc = CivPerms.civOf(civ, p);
                        Talent again = pending(p, pc, choice);
                        choose(pc, again);
                    } catch (CivException e) {
                        civ.messages().send(p, e.key(), e.args());
                    }
                });
    }

    private void choose(Civilization c, Talent t) {
        TalentState st = state(c);
        st.choices().put(t.level(), t.choice());
        civ.saves().save(COLLECTION, st);
        civ.stats().invalidate();
        new TalentChosenEvent(c.id(), t.level(), t.choice()).call();
        CivPerms.tellCiv(civ, c, "talent.chosen", Messages.arg("level", t.level()), Messages.arg("name", t.name()));
    }

    // --- effects ----------------------------------------------------------------------------------

    @Override
    public void contribute(EffectSink sink) {
        int worldWonders = -1;
        for (Civilization c : civ.state().civs()) {
            TalentState st = states.get(c.id());
            if (st == null || st.choices().isEmpty()) continue;
            int chosen = st.choices().size();
            for (Map.Entry<Integer, Integer> e : st.choices().entrySet()) {
                Level level = levels.get(e.getKey());
                Talent t = level == null ? null : level.choices().get(e.getValue());
                if (t == null) continue;
                String source = "talent:" + t.level() + "." + t.choice();
                for (EffectSpec spec : t.effects()) {
                    if (spec.minTalents() > chosen || !spec.appliesTo(c.government())) continue;
                    String per = spec.per();
                    if (per == null) {
                        spec.apply(sink, c, civ.state().capital(c), 1, source);
                    } else if (per.equals("science_buildings") || per.equals("culture_buildings")) {
                        List<String> types = wonders.group(per.replace('_', '-')).stream().map(wonders::type).toList();
                        for (Town town : civ.state().towns(c)) {
                            int n = wonders.countAll(town, types);
                            double f = spec.perFactor(n);
                            if (f > 0) sink.town(town, spec.modifier(f, source));
                        }
                    } else if (per.equals("world_wonders")) {
                        if (worldWonders < 0) worldWonders = wonders.worldWondersInWorld();
                        double f = spec.perFactor(worldWonders);
                        if (f > 0) spec.apply(sink, c, null, f, source);
                    } else if (per.equals("towns")) {
                        double f = spec.perFactor(civ.state().towns(c).size());
                        if (f > 0) spec.apply(sink, c, null, f, source);
                    }
                }
            }
        }
    }

    // --- commands ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> command() {
        return Cmd.literal("talent")
                .executes(Cmd.player((p, ctx) -> openMenu(p)))
                .then(Cmd.literal("choose")
                        .executes(Cmd.player((p, ctx) -> describe(p)))
                        .then(Cmd.arg("n", IntegerArgumentType.integer(1, 3))
                                .executes(Cmd.player((p, ctx) -> confirmChoice(p, IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> list(p))))
                .then(Cmd.literal("admin").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.literal("reset").then(Cmd.arg("civ", StringArgumentType.word())
                                .suggests(Cmd.suggest(() -> civ.state().civs().stream().map(Civilization::name).toList()))
                                .executes(Cmd.run(ctx -> adminReset(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "civ"), 0)))
                                .then(Cmd.arg("level", IntegerArgumentType.integer(1, 10))
                                        .executes(Cmd.run(ctx -> adminReset(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "civ"),
                                                IntegerArgumentType.getInteger(ctx, "level"))))))));
    }

    private void openMenu(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        refresh(c);
        new TalentMenu(this).open(p);
    }

    private void describe(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        int lvl = availableLevel(c);
        if (lvl == 0) throw new CivException("talent.error.none-available");
        Level level = levels.get(lvl);
        civ.messages().send(p, "talent.describe-header", Messages.arg("level", lvl), Messages.arg("theme", level.theme()));
        for (Talent t : level.choices().values()) {
            civ.messages().sendRaw(p, "talent.describe-line", Messages.arg("n", t.choice()), Messages.arg("name", t.name()),
                    Messages.arg("description", String.join("; ", t.description())));
        }
        civ.messages().sendRaw(p, "talent.describe-footer");
    }

    private void list(Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        TalentState st = state(c);
        civ.messages().send(p, "talent.list-header", Messages.arg("count", st.choices().size()),
                Messages.arg("capital", capitalLevel(c)));
        for (Level level : levels.values()) {
            Integer choice = st.choices().get(level.level());
            if (choice != null) {
                Talent t = level.choices().get(choice);
                civ.messages().sendRaw(p, "talent.list-chosen", Messages.arg("level", level.level()),
                        Messages.arg("name", t == null ? "?" : t.name()));
            } else if (level.level() < st.maxLevelReached()) {
                civ.messages().sendRaw(p, "talent.list-lost", Messages.arg("level", level.level()));
            }
        }
    }

    private void adminReset(org.bukkit.command.CommandSender sender, String civName, int level) throws CivException {
        Civilization c = civ.state().civByName(civName);
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", civName));
        TalentState st = state(c);
        if (level == 0) st.choices().clear();
        else st.choices().remove(level);
        st.maxLevelReached(level == 0 ? 0 : Math.min(st.maxLevelReached(), level));
        civ.saves().save(COLLECTION, st);
        civ.stats().invalidate();
        refresh(c);
        civ.messages().send(sender, "talent.admin-reset", Messages.arg("civ", c.name()));
    }
}
