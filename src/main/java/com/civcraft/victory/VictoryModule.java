package com.civcraft.victory;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.clock.GameClock;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.event.VictoryEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.religion.ReligionApi;
import com.civcraft.science.CivCommandGraft;
import com.civcraft.science.CivPerms;
import com.civcraft.science.ScienceModule;
import com.civcraft.science.TechTree;
import com.civcraft.science.WonderIndex;
import com.civcraft.space.SpaceApi;
import com.civcraft.structure.StructureApi;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The six victories (spec 03 §11): daily condition checks, countdowns with hold periods, cancellation,
 * the announcement boss bar, winner rewards ([WON] prefix, hall of winners) and {@code /civ victory}.
 */
public final class VictoryModule implements Module, VictoryApi, Listener {

    public static final String COLLECTION = "victory";
    public static final List<String> TYPES = List.of("religious", "economic", "diplomatic", "cultural", "scientific", "domination");

    record Evaluation(boolean met, List<Component> lines) {
    }

    private CivCraft civ;
    private WonderIndex wonders;
    private VictoryState state = new VictoryState();
    private BossBar bar;

    @Override
    public String id() {
        return "victory";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("victory");
        civ.store().createCollection(COLLECTION);
        List<VictoryState> loaded = civ.store().loadAll(COLLECTION, VictoryState.class);
        if (!loaded.isEmpty()) state = loaded.getFirst();
        state.repair();
        if (state.phaseStart() == null) {
            state.phaseStart(Instant.now());
            save();
        }
    }

    @Override
    public void enable(CivCraft civ) {
        wonders = new WonderIndex(civ);
        civ.listen(this);
        civ.clock().daily(GameClock.CONSEQUENCES, "victory-check", this::dailyCheck);
        civ.clock().everyMinute("victory-countdowns", this::minuteTick);
        CivCommandGraft.add(civ, this::command);
    }

    @Override
    public void disable(CivCraft civ) {
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    private void save() {
        civ.saves().save(COLLECTION, state);
    }

    private ConfigurationSection cfg() {
        return civ.balance().file("victory");
    }

    Instant phaseStart() {
        String configured = cfg().getString("phase-start", "");
        if (configured != null && !configured.isBlank()) {
            try {
                return LocalDate.parse(configured.trim()).atStartOfDay(civ.clock().zone()).toInstant();
            } catch (DateTimeParseException e) {
                civ.logger().warning("victory.yml phase-start is not an ISO date: " + configured);
            }
        }
        return state.phaseStart();
    }

    Duration hold(String type) {
        return Duration.ofDays(cfg().getLong("hold-days." + type, 21));
    }

    private Instant launchAllowedAt() {
        return phaseStart().plus(Duration.ofDays(cfg().getLong("start-after-days", 21)));
    }

    // --- VictoryApi -------------------------------------------------------------------------------

    @Override
    public List<String> types() {
        return TYPES;
    }

    @Override
    public List<Countdown> countdowns() {
        Instant now = Instant.now();
        List<Countdown> result = new ArrayList<>();
        for (VictoryState.Countdown cd : state.countdowns()) {
            long remaining = hold(cd.type).toMillis() - cd.elapsedMillis(now);
            result.add(new Countdown(cd.civId, cd.type, cd.startedAt, Duration.ofMillis(Math.max(0, remaining)), cd.pausedAt != null));
        }
        result.sort(Comparator.comparing(Countdown::remaining));
        return result;
    }

    @Override
    public Duration timeToVictory(Civilization c) {
        Duration best = null;
        for (Countdown cd : countdowns()) {
            if (cd.civId().equals(c.id()) && (best == null || cd.remaining().compareTo(best) < 0)) best = cd.remaining();
        }
        return best;
    }

    @Override
    public boolean anyCountdown() {
        return !state.countdowns().isEmpty();
    }

    @Override
    public boolean phaseOver() {
        return state.winner() != null;
    }

    @Override
    public Component wonPrefix(UUID player) {
        String color = state.wonPrefixes().get(player.toString());
        if (color == null) return null;
        return Component.text("[WON]", NamedTextColor.NAMES.valueOr(color, NamedTextColor.GOLD));
    }

    @Override
    public List<HallRecord> hall() {
        List<HallRecord> result = new ArrayList<>();
        for (Map<?, ?> m : cfg().getMapList("hall-of-winners")) {
            result.add(new HallRecord(str(m.get("phase")), str(m.get("server")), str(m.get("civ")), str(m.get("type")),
                    str(m.get("leader")), str(m.get("date"))));
        }
        for (VictoryState.Record r : state.hall()) {
            result.add(new HallRecord(r.phase, r.server, r.civ, r.type, r.leader, r.date));
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // --- score ------------------------------------------------------------------------------------

    /** Scores computed once per daily check (domination compares every civ with every other). */
    private Map<String, Long> scoreCache;

    @Override
    public long score(Civilization c) {
        if (scoreCache != null) return scoreCache.computeIfAbsent(c.id(), id -> computeScore(c));
        return computeScore(c);
    }

    private long computeScore(Civilization c) {
        ConfigurationSection s = cfg().getConfigurationSection("score");
        if (s == null) return 0;
        ConfigurationSection points = s.getConfigurationSection("structures");
        StructureApi api = wonders.api();
        double total = 0;
        double coinsPerPoint = Math.max(0.0001, s.getDouble("coins-per-point", 5));
        for (Town t : civ.state().towns(c)) {
            total += civ.state().claimCount(t) * s.getDouble("town-chunk", 200);
            total += civ.culture().chunks(t).size() * s.getDouble("culture-chunk", 100);
            total += t.residents().size() * s.getDouble("resident", 5000);
            total += Math.max(0, Money.toCoins(t.treasury())) / coinsPerPoint;
            if (api != null && points != null) {
                for (StructureApi.Placed p : api.of(t)) {
                    if (!p.complete()) continue;
                    String key = wonders.keyOfType(p.type());
                    total += points.getDouble(key != null ? key : p.type(), 0);
                }
            }
        }
        ScienceModule science = civ.module(ScienceModule.class);
        double perBeaker = s.getDouble("tech-per-beaker", 0.25);
        for (String id : science.state(c).completed()) {
            TechTree.Tech t = science.tree().get(id);
            if (t != null) total += t.beakers() * perBeaker;
        }
        return Math.round(total);
    }

    // --- conditions -------------------------------------------------------------------------------

    private static boolean eligible(Town t) {
        return t.status() != TownStatus.CAPTURED && t.status() != TownStatus.BOUGHT;
    }

    private Component line(boolean ok, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... args) {
        return civ.messages().component(ok ? "victory.cond-ok" : "victory.cond-no",
                Messages.arg("text", civ.messages().component("victory.cond." + key, args)));
    }

    Evaluation evaluate(Civilization c, String type) {
        List<Component> lines = new ArrayList<>();
        boolean met = true;
        boolean base = !c.isProvince() && !c.isConquered();
        lines.add(line(base, "civ"));
        met &= base;
        Predicate<Town> ok = VictoryModule::eligible;
        switch (type) {
            case "religious" -> {
                ReligionApi religion = civ.apiOrNull(ReligionApi.class);
                String rel = religion == null ? null : religion.religion(c);
                boolean hasRel = rel != null;
                lines.add(line(hasRel, "religion", Messages.arg("religion", religion == null ? "—" : religion.religionName(rel))));
                boolean wonder = wonders.townWith(c, cfg().getString("religious.wonder", "notre_dame"), ok) != null;
                lines.add(line(wonder, "wonder", Messages.arg("wonder", wonderName(cfg().getString("religious.wonder", "notre_dame")))));
                double need = cfg().getDouble("religious.min-points", 45000);
                double have = religion == null ? 0 : religion.points(c);
                boolean points = have >= need;
                lines.add(line(points, "religion-points", Messages.number("have", have), Messages.number("need", need)));
                boolean first = religion != null && hasRel && religion.place(c) == 1;
                lines.add(line(first, "religion-first", Messages.arg("place", religion == null ? 0 : religion.place(c))));
                met &= hasRel && wonder && points && first;
            }
            case "economic" -> {
                String key = cfg().getString("economic.wonder", "world_bank");
                int need = cfg().getInt("economic.min-level", 5);
                int level = 0;
                for (Town t : civ.state().towns(c)) {
                    if (!eligible(t)) continue;
                    for (StructureApi.Placed p : wonders.completed(t, wonders.type(key))) level = Math.max(level, p.level());
                }
                boolean wonder = level > 0;
                lines.add(line(wonder, "wonder", Messages.arg("wonder", wonderName(key))));
                lines.add(line(level >= need, "wonder-level", Messages.arg("have", level), Messages.arg("need", need)));
                met &= wonder && level >= need;
            }
            case "diplomatic" -> {
                String key = cfg().getString("diplomatic.wonder", "council_of_eight");
                boolean wonder = wonders.townWith(c, key, ok) != null;
                lines.add(line(wonder, "wonder", Messages.arg("wonder", wonderName(key))));
                met &= wonder;
            }
            case "cultural" -> {
                String key = cfg().getString("cultural.wonder", "burj_al_arab");
                boolean wonder = wonders.townWith(c, key, ok) != null;
                lines.add(line(wonder, "wonder", Messages.arg("wonder", wonderName(key))));
                double culture = 0;
                for (Town t : civ.state().towns(c)) {
                    if (t.status() == TownStatus.NATIVE && c.id().equals(t.nativeCivId())) culture += t.culture();
                }
                double need = cfg().getDouble("cultural.min-culture", 13_000_000);
                lines.add(line(culture >= need, "culture", Messages.number("have", culture), Messages.number("need", need)));
                met &= wonder && culture >= need;
            }
            case "scientific" -> {
                SpaceApi space = civ.apiOrNull(SpaceApi.class);
                Town capital = civ.state().capital(c);
                boolean shuttle = space != null && capital != null && eligible(capital) && space.hasShuttleInCapital(c);
                lines.add(line(shuttle, "shuttle"));
                int done = space == null ? 0 : space.completedMissions(c);
                int total = space == null ? 7 : space.missionCount();
                lines.add(line(done >= total, "missions", Messages.arg("have", done), Messages.arg("need", total)));
                double beakers = weightedScience(c);
                double need = cfg().getDouble("scientific.min-beakers", 20000);
                lines.add(line(beakers >= need, "beakers", Messages.number("have", beakers), Messages.number("need", need)));
                met &= shuttle && done >= total && beakers >= need;
            }
            case "domination" -> {
                long own = score(c);
                long second = 0;
                for (Civilization other : civ.state().civs()) {
                    if (!other.id().equals(c.id())) second = Math.max(second, score(other));
                }
                double ratio = cfg().getDouble("domination.score-ratio", 9);
                boolean scoreOk = own > 0 && own >= ratio * second;
                lines.add(line(scoreOk, "score", Messages.arg("have", Format.number(own)),
                        Messages.arg("need", Format.number(ratio * second))));
                Set<String> excluded = new HashSet<>(cfg().getStringList("domination.excluded-wonders"));
                int count = wonders.worldWonders(c, ok, excluded);
                int need = cfg().getInt("domination.min-wonders", 6);
                lines.add(line(count >= need, "wonders", Messages.arg("have", count), Messages.arg("need", need)));
                met &= scoreOk && count >= need;
            }
            default -> met = false;
        }
        return new Evaluation(met, lines);
    }

    /** Science per hour with native towns at 100 % and others at the configured weight (bought excluded). */
    double weightedScience(Civilization c) {
        ScienceModule science = civ.module(ScienceModule.class);
        double weight = cfg().getDouble("scientific.non-native-weight", 0.3);
        double sum = 0;
        for (Town t : civ.state().towns(c)) {
            if (t.status() == TownStatus.BOUGHT) continue;
            boolean nativeTown = t.status() == TownStatus.NATIVE && c.id().equals(t.nativeCivId());
            sum += science.townRate(c, t) * (nativeTown ? 1 : weight);
        }
        return sum * science.civMultiplier(c);
    }

    String wonderName(String key) {
        String k = "victory.wonder." + key;
        return civ.messages().has(k) ? civ.messages().raw(k) : key;
    }

    Component typeName(String type) {
        return civ.messages().component("victory.type." + type);
    }

    // --- clock ------------------------------------------------------------------------------------

    private VictoryState.Countdown find(String civId, String type) {
        for (VictoryState.Countdown cd : state.countdowns()) {
            if (cd.civId.equals(civId) && cd.type.equals(type)) return cd;
        }
        return null;
    }

    /** Daily check at tax time: start, keep or cancel countdowns and declare finished victories. */
    void dailyCheck() {
        if (state.winner() != null) return;
        scoreCache = new java.util.HashMap<>();
        try {
            runDailyCheck();
        } finally {
            scoreCache = null;
        }
    }

    private void runDailyCheck() {
        Instant now = Instant.now();
        boolean launchOpen = !now.isBefore(launchAllowedAt());
        for (Civilization c : List.copyOf(civ.state().civs())) {
            for (String type : TYPES) {
                VictoryState.Countdown cd = find(c.id(), type);
                boolean met = evaluate(c, type).met();
                if (met && cd == null && launchOpen) {
                    state.countdowns().add(new VictoryState.Countdown(c.id(), type));
                    CivPerms.broadcast(civ, "victory.countdown-started", Messages.arg("civ", c.name()),
                            Messages.arg("type", typeName(type)),
                            Messages.arg("time", Durations.format(hold(type))));
                } else if (!met && cd != null) {
                    boolean shuttleGone = type.equals("scientific") && !shuttlePresent(c);
                    if (shuttleGone) {
                        pause(cd);
                        continue;
                    }
                    cancel(cd, c, "conditions");
                }
            }
        }
        state.countdowns().removeIf(cd -> civ.state().civ(cd.civId) == null);
        save();
        List<VictoryState.Countdown> finished = new ArrayList<>();
        for (VictoryState.Countdown cd : state.countdowns()) {
            if (cd.pausedAt == null && cd.elapsedMillis(now) >= hold(cd.type).toMillis()) finished.add(cd);
        }
        if (!finished.isEmpty()) declare(finished, now);
        updateBar();
    }

    private boolean shuttlePresent(Civilization c) {
        SpaceApi space = civ.apiOrNull(SpaceApi.class);
        return space != null && space.hasShuttleInCapital(c);
    }

    private void pause(VictoryState.Countdown cd) {
        if (cd.pausedAt != null) return;
        cd.pausedAt = Instant.now();
        Civilization c = civ.state().civ(cd.civId);
        if (c != null) {
            CivPerms.broadcast(civ, "victory.countdown-paused", Messages.arg("civ", c.name()), Messages.arg("type", typeName(cd.type)));
        }
        save();
    }

    private void resume(VictoryState.Countdown cd) {
        if (cd.pausedAt == null) return;
        cd.pausedMillis += Math.max(0, Instant.now().toEpochMilli() - cd.pausedAt.toEpochMilli());
        cd.pausedAt = null;
        Civilization c = civ.state().civ(cd.civId);
        if (c != null) {
            CivPerms.broadcast(civ, "victory.countdown-resumed", Messages.arg("civ", c.name()), Messages.arg("type", typeName(cd.type)));
        }
        save();
    }

    private void cancel(VictoryState.Countdown cd, Civilization c, String reason) {
        state.countdowns().remove(cd);
        save();
        CivPerms.broadcast(civ, "victory.countdown-cancelled", Messages.arg("civ", c == null ? cd.civId : c.name()),
                Messages.arg("type", typeName(cd.type)), Messages.arg("reason", civ.messages().component("victory.reason." + reason)));
        updateBar();
    }

    private void minuteTick() {
        if (state.winner() != null) {
            updateBar();
            return;
        }
        for (VictoryState.Countdown cd : List.copyOf(state.countdowns())) {
            Civilization c = civ.state().civ(cd.civId);
            if (c == null) {
                state.countdowns().remove(cd);
                save();
                continue;
            }
            if (c.isConquered() || c.isProvince()) {
                cancel(cd, c, "captured");
                continue;
            }
            if (cd.type.equals("scientific")) {
                if (shuttlePresent(c)) resume(cd);
                else pause(cd);
            }
        }
        updateBar();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStructureDestroyed(StructureDestroyedEvent event) {
        String key = wonders.keyOfType(event.type());
        if (key == null || state.winner() != null) return;
        for (VictoryState.Countdown cd : List.copyOf(state.countdowns())) {
            String wonder = cfg().getString(cd.type + ".wonder");
            if (!key.equals(wonder)) continue;
            Civilization c = civ.state().civ(cd.civId);
            if (c == null) continue;
            civ.tasks().nextTick(() -> {
                if (!state.countdowns().contains(cd)) return;
                if (cd.type.equals("scientific")) {
                    if (!shuttlePresent(c)) pause(cd);
                } else if (wonders.townWith(c, wonder, VictoryModule::eligible) == null) {
                    cancel(cd, c, "wonder");
                }
            });
        }
    }

    // --- victory ----------------------------------------------------------------------------------

    private void declare(List<VictoryState.Countdown> finished, Instant now) {
        long best = Long.MAX_VALUE;
        for (VictoryState.Countdown cd : finished) {
            best = Math.min(best, finishTime(cd));
        }
        List<VictoryState.Countdown> winners = new ArrayList<>();
        for (VictoryState.Countdown cd : finished) {
            if (finishTime(cd) - best < 60_000) winners.add(cd);
        }
        VictoryState.Winner w = new VictoryState.Winner();
        w.type = winners.getFirst().type;
        w.at = now;
        w.draw = winners.size() > 1;
        String date = LocalDate.now(civ.clock().zone()).toString();
        List<String> colors = cfg().getStringList("won-colors");
        for (VictoryState.Countdown cd : winners) {
            Civilization c = civ.state().civ(cd.civId);
            if (c == null) continue;
            w.civIds.add(c.id());
            w.civNames.add(c.name());
            Resident owner = c.owner() == null ? null : civ.state().resident(c.owner());
            if (owner != null) {
                String color = colors.isEmpty() ? "gold" : colors.get(Math.floorMod(state.nextColorIndex(), colors.size()));
                state.wonPrefixes().put(owner.uuid().toString(), color);
                owner.setting("victory_won", true);
                civ.state().save(owner);
            }
            VictoryState.Record r = new VictoryState.Record();
            r.phase = cfg().getString("phase-name", "");
            r.server = cfg().getString("server-name", "");
            r.civ = c.name();
            r.type = cd.type;
            r.leader = owner == null ? "" : owner.name();
            r.date = date;
            state.hall().add(r);
            for (String command : cfg().getStringList("reward-commands")) {
                String cmd = command.replace("{player}", owner == null ? "" : owner.name())
                        .replace("{civ}", c.name()).replace("{type}", cd.type);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (RuntimeException e) {
                    civ.logger().log(Level.WARNING, "Victory reward command failed: " + cmd, e);
                }
            }
        }
        state.winner(w);
        state.countdowns().clear();
        save();
        String names = String.join(", ", w.civNames);
        CivPerms.broadcast(civ, w.draw ? "victory.draw" : "victory.won", Messages.arg("civ", names),
                Messages.arg("type", typeName(w.type)));
        Title title = Title.title(civ.messages().component("victory.title", Messages.arg("civ", names)),
                civ.messages().component("victory.subtitle", Messages.arg("type", typeName(w.type))));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
        new VictoryEvent(w.civIds, w.type, w.draw).call();
        updateBar();
    }

    private long finishTime(VictoryState.Countdown cd) {
        return cd.startedAt.toEpochMilli() + cd.pausedMillis + hold(cd.type).toMillis();
    }

    // --- boss bar ---------------------------------------------------------------------------------

    private void updateBar() {
        if (!cfg().getBoolean("bossbar.enabled", true) || state.winner() != null || state.countdowns().isEmpty()) {
            if (bar != null) {
                for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
                bar = null;
            }
            return;
        }
        Countdown next = countdowns().getFirst();
        Civilization c = civ.state().civ(next.civId());
        Component name = civ.messages().component(next.paused() ? "victory.bar-paused" : "victory.bar",
                Messages.arg("civ", c == null ? next.civId() : c.name()), Messages.arg("type", typeName(next.type())),
                Messages.arg("time", Durations.format(next.remaining())));
        float progress = (float) Math.max(0, Math.min(1, 1 - next.remaining().toMillis() / (double) hold(next.type()).toMillis()));
        if (bar == null) {
            BossBar.Color color = parseEnum(BossBar.Color.class, cfg().getString("bossbar.color", "YELLOW"), BossBar.Color.YELLOW);
            BossBar.Overlay overlay = parseEnum(BossBar.Overlay.class, cfg().getString("bossbar.overlay", "PROGRESS"), BossBar.Overlay.PROGRESS);
            bar = BossBar.bossBar(name, progress, color, overlay);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return def;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bar != null) event.getPlayer().showBossBar(bar);
    }

    // --- commands ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> command() {
        return Cmd.literal("victory")
                .executes(Cmd.run(ctx -> overview(ctx.getSource().getSender())))
                .then(Cmd.literal("status").executes(Cmd.player((p, ctx) -> status(p, null))))
                .then(Cmd.literal("winners").executes(Cmd.run(ctx -> winners(ctx.getSource().getSender()))))
                .then(Cmd.literal("admin").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.literal("check").executes(Cmd.run(ctx -> {
                            dailyCheck();
                            civ.messages().send(ctx.getSource().getSender(), "victory.admin-checked");
                        })))
                        .then(Cmd.literal("reset").executes(Cmd.run(ctx -> {
                            state.countdowns().clear();
                            state.winner(null);
                            state.phaseStart(Instant.now());
                            save();
                            updateBar();
                            civ.messages().send(ctx.getSource().getSender(), "victory.admin-reset");
                        }))))
                .then(Cmd.arg("type", StringArgumentType.greedyString())
                        .suggests(Cmd.suggest(() -> {
                            List<String> names = new ArrayList<>(TYPES);
                            for (String t : TYPES) names.add(civ.messages().plain("victory.type." + t));
                            return names;
                        }))
                        .executes(Cmd.run(ctx -> typeStatus(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "type")))));
    }

    private String parseType(String input) throws CivException {
        String q = input.trim().toLowerCase(Locale.ROOT);
        for (String t : TYPES) {
            if (t.equals(q) || civ.messages().plain("victory.type." + t).toLowerCase(Locale.ROOT).startsWith(q)) return t;
        }
        throw new CivException("victory.error.unknown-type", Messages.arg("type", input));
    }

    private void overview(CommandSender sender) {
        VictoryState.Winner w = state.winner();
        if (w != null) {
            civ.messages().send(sender, "victory.over", Messages.arg("civ", String.join(", ", w.civNames)),
                    Messages.arg("type", typeName(w.type)));
            return;
        }
        Instant allowed = launchAllowedAt();
        if (Instant.now().isBefore(allowed)) {
            civ.messages().send(sender, "victory.not-yet", Messages.arg("time",
                    Durations.format(Duration.between(Instant.now(), allowed))));
        }
        List<Countdown> list = countdowns();
        if (list.isEmpty()) {
            civ.messages().send(sender, "victory.none");
        } else {
            civ.messages().send(sender, "victory.countdowns-header");
            for (Countdown cd : list) {
                Civilization c = civ.state().civ(cd.civId());
                civ.messages().sendRaw(sender, cd.paused() ? "victory.countdown-line-paused" : "victory.countdown-line",
                        Messages.arg("civ", c == null ? cd.civId() : c.name()), Messages.arg("type", typeName(cd.type())),
                        Messages.arg("time", Durations.format(cd.remaining())));
            }
        }
        civ.messages().sendRaw(sender, "victory.help");
    }

    private void status(Player p, String type) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        civ.messages().send(p, "victory.status-header", Messages.arg("civ", c.name()));
        for (String t : type == null ? TYPES : List.of(type)) {
            Evaluation e = evaluate(c, t);
            VictoryState.Countdown cd = find(c.id(), t);
            Component countdown = cd == null ? civ.messages().component("victory.no-countdown")
                    : civ.messages().component("victory.in-countdown", Messages.arg("time", Durations.format(Duration.ofMillis(
                    Math.max(0, hold(t).toMillis() - cd.elapsedMillis(Instant.now()))))));
            civ.messages().sendRaw(p, e.met() ? "victory.status-type-ok" : "victory.status-type-no",
                    Messages.arg("type", typeName(t)), Messages.arg("countdown", countdown));
            for (Component line : e.lines()) p.sendMessage(Component.text("   ").append(line));
        }
    }

    private void typeStatus(CommandSender sender, String input) throws CivException {
        String type = parseType(input);
        civ.messages().send(sender, "victory.type-header", Messages.arg("type", typeName(type)),
                Messages.arg("hold", Durations.format(hold(type))));
        civ.messages().sendRaw(sender, "victory.type-desc." + type);
        for (Countdown cd : countdowns()) {
            if (!cd.type().equals(type)) continue;
            Civilization c = civ.state().civ(cd.civId());
            civ.messages().sendRaw(sender, cd.paused() ? "victory.countdown-line-paused" : "victory.countdown-line",
                    Messages.arg("civ", c == null ? cd.civId() : c.name()), Messages.arg("type", typeName(type)),
                    Messages.arg("time", Durations.format(cd.remaining())));
        }
        if (sender instanceof Player p && civ.state().civOf(p) != null) status(p, type);
    }

    private void winners(CommandSender sender) {
        List<HallRecord> hall = hall();
        if (hall.isEmpty()) {
            civ.messages().send(sender, "victory.hall-empty");
            return;
        }
        civ.messages().send(sender, "victory.hall-header");
        for (HallRecord r : hall) {
            Component type = civ.messages().has("victory.type." + r.type()) ? typeName(r.type()) : Component.text(r.type());
            civ.messages().sendRaw(sender, "victory.hall-line", Messages.arg("phase", r.phase()), Messages.arg("server", r.server()),
                    Messages.arg("civ", r.civ()), Messages.arg("type", type), Messages.arg("leader", r.leader()),
                    Messages.arg("date", r.date()));
        }
    }
}
