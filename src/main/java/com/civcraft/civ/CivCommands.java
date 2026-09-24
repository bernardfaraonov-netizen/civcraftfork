package com.civcraft.civ;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.diplomacy.DiplomacyModule;
import com.civcraft.diplomacy.VictoryStatus;
import com.civcraft.economy.Amounts;
import com.civcraft.economy.EconomyMath;
import com.civcraft.economy.Ledger;
import com.civcraft.economy.Production;
import com.civcraft.event.RevolutionEvent;
import com.civcraft.government.GovernmentModule;
import com.civcraft.model.Civilization;
import com.civcraft.model.Relation;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.science.ResearchApi;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownData;
import com.civcraft.town.TownModule;
import com.civcraft.town.Upgrades;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The {@code /civ} ({@code /c}) command tree (spec §19.1) and {@code /market}. */
final class CivCommands {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final List<String> TAG_COLORS = List.of("white", "yellow", "light_purple", "green", "gold", "dark_green",
            "aqua", "dark_purple");

    private final CivCraft civ;
    private final CivModule module;

    CivCommands(CivCraft civ, CivModule module) {
        this.civ = civ;
        this.module = module;
    }

    void register() {
        CmdKit.register(civ, this::tree, "Civilization commands", List.of("c"));
        CmdKit.register(civ, () -> module.market().command(), "Market of towns and civilizations in debt", List.of("m"));
    }

    private TownModule towns() {
        return civ.module(TownModule.class);
    }

    private GovernmentModule gov() {
        return civ.module(GovernmentModule.class);
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        LiteralArgumentBuilder<CommandSourceStack> root = lit("civ").executes(CmdKit.help("civ.help"))
                .then(lit("help").executes(CmdKit.help("civ.help")))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p, "")))
                        .then(word("page").suggests(CmdKit.values(List.of("online", "upkeep", "joinlog", "taxes", "beakers", "revenue")))
                                .executes(Cmd.player((p, ctx) -> info(p, arg(ctx, "page").toLowerCase(Locale.ROOT))))))
                .then(lit("show").then(word("civ").suggests(CmdKit.civs()).executes(Cmd.run(ctx -> show(ctx.getSource().getSender(), arg(ctx, "civ"))))))
                .then(lit("list").executes(Cmd.run(ctx -> list(ctx.getSource().getSender()))))
                .then(lit("top5").executes(Cmd.run(ctx -> top(ctx.getSource().getSender(), 5))))
                .then(lit("topall").executes(Cmd.run(ctx -> top(ctx.getSource().getSender(), Integer.MAX_VALUE))))
                .then(lit("members").executes(Cmd.player((p, ctx) -> members(p, null)))
                        .then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> members(p, arg(ctx, "town"))))))
                .then(lit("townlist").executes(Cmd.player((p, ctx) -> townList(p))))
                .then(lit("location").executes(Cmd.player((p, ctx) -> townList(p))))
                .then(lit("time").executes(Cmd.run(ctx -> time(ctx.getSource().getSender()))))
                .then(lit("deposit").then(word("amount").executes(Cmd.player((p, ctx) -> deposit(p, arg(ctx, "amount"))))))
                .then(lit("withdraw").then(word("amount").executes(Cmd.player((p, ctx) -> withdraw(p, arg(ctx, "amount"))))))
                .then(lit("w").then(word("amount").executes(Cmd.player((p, ctx) -> withdraw(p, arg(ctx, "amount"))))))
                .then(lit("debt").executes(Cmd.player((p, ctx) -> debt(p)))
                        .then(lit("pay").executes(Cmd.player((p, ctx) -> payDebt(p, "all")))
                                .then(word("status").suggests(CmdKit.values(List.of("all", "native", "defeated", "bought", "affiliated", "not_defeated")))
                                        .executes(Cmd.player((p, ctx) -> payDebt(p, arg(ctx, "status")))))))
                .then(gov().govCommand())
                .then(gov().nationCommand())
                .then(civ.module(DiplomacyModule.class).dipCommand())
                .then(groupCommand())
                .then(permCommand())
                .then(setCommand())
                .then(lit("motd").executes(Cmd.player((p, ctx) -> showMotd(p)))
                        .then(lit("add").then(CmdKit.text("text").executes(Cmd.player((p, ctx) -> motd(p, arg(ctx, "text"), true)))))
                        .then(CmdKit.text("text").executes(Cmd.player((p, ctx) -> motd(p, arg(ctx, "text"), false)))))
                .then(lit("changeowner").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> changeOwner(p, arg(ctx, "player"))))))
                .then(lit("claimowner").executes(Cmd.player((p, ctx) -> claimOwner(p))))
                .then(lit("disbandtown").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> disbandTown(p, arg(ctx, "town"))))))
                .then(lit("revolution").executes(Cmd.player((p, ctx) -> revolution(p))))
                .then(lit("mute")
                        .then(lit("list").executes(Cmd.player((p, ctx) -> muteList(p))))
                        .then(lit("town").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> muteTown(p, arg(ctx, "town"))))))
                        .then(lit("player").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> mutePlayer(p, arg(ctx, "player")))))))
                .then(lit("warning")
                        .then(lit("show").executes(Cmd.player((p, ctx) -> warnings(p))))
                        .then(lit("add").requires(Cmd.perm("civcraft.admin")).then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.run(ctx -> warn(ctx.getSource().getSender(), arg(ctx, "civ"), 1)))))
                        .then(lit("remove").requires(Cmd.perm("civcraft.admin")).then(word("civ").suggests(CmdKit.civs())
                                .executes(Cmd.run(ctx -> warn(ctx.getSource().getSender(), arg(ctx, "civ"), -1))))))
                .then(lit("teleport").executes(Cmd.player((p, ctx) -> teleport(p))))
                .then(lit("tp").executes(Cmd.player((p, ctx) -> teleport(p))))
                .then(lit("tiles").executes(Cmd.player((p, ctx) -> tiles(p))))
                .then(lit("stats").executes(Cmd.player((p, ctx) -> stats(p))))
                .then(lit("progress").executes(Cmd.player((p, ctx) -> progress(p))))
                .then(lit("sort").executes(Cmd.player((p, ctx) -> sort(p, "score")))
                        .then(word("field").suggests(CmdKit.values(List.of("score", "money", "hammers", "beakers", "growth", "culturerate",
                                "happiness", "tax", "culture"))).executes(Cmd.player((p, ctx) -> sort(p, arg(ctx, "field"))))))
                .then(lit("scouts").executes(Cmd.player((p, ctx) -> scouts(p))));
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof CivCommandExtension ext) ext.extendCiv(root);
        }
        return root;
    }

    private Civilization myCiv(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        return c;
    }

    private Resident resident(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        if (r == null) throw new CivException("error.internal");
        return r;
    }

    private void requireRank(Player p, Civilization c, Civilization.Rank rank) throws CivException {
        if (!c.rank(p.getUniqueId()).atLeast(rank)) {
            throw new CivException(rank == Civilization.Rank.OWNER ? "civ.owner-only"
                    : rank == Civilization.Rank.LEADER ? "civ.leader-only" : "civ.adviser-only");
        }
    }

    private String name(UUID id) {
        Resident r = id == null ? null : civ.state().resident(id);
        return r == null ? "-" : r.name();
    }

    private String names(Set<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) names.add(name(id));
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    // --- info -----------------------------------------------------------------------------------

    private void info(Player p, String page) throws CivException {
        Civilization c = myCiv(p);
        Messages m = civ.messages();
        switch (page) {
            case "" -> overview(p, c);
            case "online" -> {
                List<String> online = new ArrayList<>();
                for (Player pl : Channels.civMembers(c)) online.add(pl.getName());
                m.send(p, "civ.info.online", Messages.arg("count", online.size()), Messages.arg("list", String.join(", ", online)));
            }
            case "upkeep" -> {
                requireRank(p, c, Civilization.Rank.ADVISER);
                long total = 0;
                for (Town t : civ.state().towns(c)) {
                    long u = towns().dailyUpkeep(t);
                    total += u;
                    m.sendRaw(p, "civ.info.upkeep-town", Messages.arg("town", t.name()), Messages.money("amount", u), Messages.money("debt", t.debt()));
                }
                m.sendRaw(p, "civ.info.upkeep-total", Messages.money("amount", total));
            }
            case "joinlog" -> {
                requireRank(p, c, Civilization.Rank.ADVISER);
                List<String> log = module.data(c).joinLog();
                m.send(p, "civ.info.joinlog-header");
                for (int i = Math.max(0, log.size() - 20); i < log.size(); i++) {
                    String[] e = log.get(i).split("\\|", 4);
                    if (e.length < 4) continue;
                    m.sendRaw(p, "civ.info.joinlog-entry", Messages.arg("time", TIME.format(Instant.ofEpochSecond(Long.parseLong(e[0])).atZone(civ.clock().zone()))),
                            Messages.arg("action", m.plain("civ.joinlog.action." + e[1])), Messages.arg("name", e[2]), Messages.arg("town", e[3]));
                }
            }
            case "taxes" -> {
                requireRank(p, c, Civilization.Rank.ADVISER);
                long[] last = towns().lastTaxes(c);
                m.sendRaw(p, "civ.info.taxes", Messages.arg("taxes", Format.percent(c.taxes())),
                        Messages.arg("effective", Format.percent(gov().effectiveTaxes(c))), Messages.arg("max", Format.percent(gov().maxTax(c))),
                        Messages.arg("science", Format.percent(c.science())), Messages.number("price", gov().beakerPrice(c)),
                        Messages.money("collected", last[0]), Messages.money("converted", last[1]));
            }
            case "beakers" -> {
                double own = 0;
                double tax = 0;
                for (Town t : civ.state().towns(c)) {
                    Production.Figures f = towns().production().figures(t);
                    own += f.beakers();
                    EconomyMath.TaxSplit split = EconomyMath.splitIncome(Money.ofCoins(f.income()), gov().effectiveTaxes(c), c.science());
                    tax += EconomyMath.beakers(split.scienceCoins(), gov().beakerPrice(c));
                }
                m.send(p, "civ.info.beakers", Messages.number("own", own), Messages.number("tax", tax), Messages.number("total", own + tax));
            }
            case "revenue" -> {
                requireRank(p, c, Civilization.Rank.ADVISER);
                double income = 0;
                long toCiv = 0;
                long science = 0;
                long upkeep = 0;
                for (Town t : civ.state().towns(c)) {
                    if (t.isCaptured()) continue;
                    Production.Figures f = towns().production().figures(t);
                    income += f.income();
                    EconomyMath.TaxSplit split = EconomyMath.splitIncome(Money.ofCoins(f.income()), gov().effectiveTaxes(c), c.science());
                    toCiv += split.civTreasury();
                    science += split.scienceCoins();
                    upkeep += towns().dailyUpkeep(t);
                }
                m.sendRaw(p, "civ.info.revenue", Messages.number("income", income), Messages.money("civ", toCiv),
                        Messages.money("science", science), Messages.money("day", toCiv * 24), Messages.money("upkeep", upkeep));
            }
            default -> throw new CivException("civ.info.unknown-page", Messages.arg("page", page));
        }
    }

    private void overview(CommandSender viewer, Civilization c) {
        Messages m = civ.messages();
        Town capital = civ.state().capital(c);
        List<Town> list = civ.state().towns(c);
        int residents = list.stream().mapToInt(t -> t.residents().size()).sum();
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        m.sendRaw(viewer, "civ.info.header", Messages.arg("civ", c.name()), Messages.arg("tag", c.tag() == null ? "-" : c.tag()),
                Messages.arg("kind", m.plain(c.isProvince() ? "civ.kind.province" : "civ.kind.civ")));
        m.sendRaw(viewer, "civ.info.leaders", Messages.arg("owner", name(c.owner())), Messages.arg("leaders", names(c.leaders())),
                Messages.arg("advisers", names(c.advisers())));
        m.sendRaw(viewer, "civ.info.towns", Messages.arg("capital", capital == null ? "-" : capital.name()),
                Messages.arg("towns", list.size()), Messages.arg("residents", residents));
        var g = gov().current(c);
        var nation = gov().nation(c);
        m.sendRaw(viewer, "civ.info.government", Messages.arg("gov", g.name()), Messages.arg("nation", nation == null ? "-" : nation.name()),
                Messages.arg("era", research == null ? "-" : String.valueOf(research.era(c))));
        boolean insider = viewer instanceof Player p && (c.rank(p.getUniqueId()).atLeast(Civilization.Rank.ADVISER) || p.hasPermission("civcraft.admin"));
        if (insider) {
            m.sendRaw(viewer, "civ.info.treasury", Messages.money("treasury", c.treasury()), Messages.arg("taxes", Format.percent(c.taxes())),
                    Messages.arg("science", Format.percent(c.science())));
        }
        if (c.targetGovernment() != null && c.governmentChangeEnds() != null) {
            m.sendRaw(viewer, "civ.info.transition", Messages.arg("time", Durations.format(Duration.between(Instant.now(), c.governmentChangeEnds()))));
        }
        if (c.isConquered()) {
            Civilization by = civ.state().civ(c.conqueredBy());
            m.sendRaw(viewer, "civ.info.conquered", Messages.arg("civ", by == null ? "?" : by.name()));
        }
        m.sendRaw(viewer, "civ.info.founded", Messages.arg("date", DATE.format(c.founded().atZone(civ.clock().zone()))));
        List<String> motd = module.data(c).motd();
        if (!motd.isEmpty()) for (String line : motd) m.sendRaw(viewer, "civ.motd.line", Messages.arg("text", line));
        int pending = civ.module(DiplomacyModule.class).pendingFor(c);
        if (pending > 0 && insider) m.sendRaw(viewer, "civ.info.pending", Messages.arg("count", pending));
    }

    private void show(CommandSender sender, String name) throws CivException {
        Civilization c = Lookup.civ(name);
        overview(sender, c);
        List<String> wars = new ArrayList<>();
        for (Relation r : civ.state().relations(c)) {
            if (r.type() == com.civcraft.model.RelationType.WAR) {
                Civilization o = civ.state().civ(r.other(c.id()));
                if (o != null) wars.add(o.name());
            }
        }
        civ.messages().sendRaw(sender, "civ.show.wars", Messages.arg("list", wars.isEmpty() ? "-" : String.join(", ", wars)));
    }

    private void list(CommandSender sender) {
        List<String> civs = new ArrayList<>();
        List<String> provinces = new ArrayList<>();
        for (Civilization c : civ.state().civs()) {
            String entry = c.name() + (c.tag() == null ? "" : " [" + c.tag() + "]");
            (c.isProvince() ? provinces : civs).add(entry);
        }
        civs.sort(String.CASE_INSENSITIVE_ORDER);
        provinces.sort(String.CASE_INSENSITIVE_ORDER);
        civ.messages().send(sender, "civ.list.civs", Messages.arg("count", civs.size()), Messages.arg("list", civs.isEmpty() ? "-" : String.join(", ", civs)));
        civ.messages().send(sender, "civ.list.provinces", Messages.arg("count", provinces.size()),
                Messages.arg("list", provinces.isEmpty() ? "-" : String.join(", ", provinces)));
    }

    private double score(Civilization c) {
        double score = 0;
        TownModule t = towns();
        for (Town town : civ.state().towns(c)) score += townScore(t, town);
        return score;
    }

    private double townScore(TownModule t, Town town) {
        double score = 0;
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof com.civcraft.economy.TownValuation v) score += v.structuresScore(town);
        }
        var s = civ.balance().section("town", "score");
        score += s.getDouble("per-resident", 5000) * town.residents().size();
        score += s.getDouble("per-claim", 200) * civ.state().claimCount(town);
        score += s.getDouble("per-culture-chunk", 100) * civ.culture().chunks(town).size();
        score += town.treasury() / 100.0 / Math.max(1, s.getDouble("coins-per-point", 5));
        return score;
    }

    /** Separate tops for civilizations and provinces (spec §5.3). */
    private void top(CommandSender sender, int n) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Civilization c : civ.state().civs()) scores.put(c.id(), score(c));
        for (boolean province : new boolean[]{false, true}) {
            List<Civilization> list = new ArrayList<>(civ.state().civs().stream().filter(c -> c.isProvince() == province).toList());
            list.sort(Comparator.comparingDouble((Civilization c) -> scores.get(c.id())).reversed());
            civ.messages().send(sender, province ? "civ.top.provinces" : "civ.top.civs");
            for (int i = 0; i < Math.min(n, list.size()); i++) {
                civ.messages().sendRaw(sender, "civ.top.entry", Messages.arg("place", i + 1), Messages.arg("civ", list.get(i).name()),
                        Messages.number("score", Math.floor(scores.get(list.get(i).id()))));
            }
        }
    }

    private void members(Player p, String townName) throws CivException {
        Civilization c = myCiv(p);
        List<Town> list;
        if (townName == null) list = civ.state().towns(c);
        else {
            Town t = Lookup.town(townName);
            Town own = civ.state().townOf(p);
            if (!Objects.equals(t.civId(), c.id())) throw new CivException("civ.members.foreign");
            if ((own == null || !own.id().equals(t.id())) && !c.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
            list = List.of(t);
        }
        for (Town t : list) {
            Component line = civ.messages().component("civ.members.town", Messages.arg("town", t.name()));
            int i = 0;
            for (UUID id : t.residents()) {
                line = line.append(Component.text(i++ == 0 ? " " : ", ")).append(civ.messages().component(
                        Bukkit.getPlayer(id) != null ? "town.members.online" : "town.members.offline", Messages.arg("name", name(id))));
            }
            p.sendMessage(line);
        }
    }

    private void townList(Player p) throws CivException {
        Civilization c = myCiv(p);
        for (Town t : civ.state().towns(c)) {
            BlockPos pos = t.center();
            civ.messages().sendRaw(p, "civ.townlist.entry", Messages.arg("town", t.name()),
                    Messages.arg("status", civ.messages().plain("town.status." + t.status().name().toLowerCase(Locale.ROOT))),
                    Messages.arg("x", pos == null ? "-" : String.valueOf(pos.x())), Messages.arg("y", pos == null ? "-" : String.valueOf(pos.y())),
                    Messages.arg("z", pos == null ? "-" : String.valueOf(pos.z())));
        }
    }

    private void time(CommandSender sender) {
        Messages m = civ.messages();
        m.send(sender, "civ.time.upkeep", Messages.arg("time", Durations.format(civ.clock().untilNextDaily())));
        m.send(sender, "civ.time.war", Messages.arg("time", civ.module(DiplomacyModule.class).untilWar()));
        ZonedDateTime now = civ.clock().now();
        String trade = until(now, civ.balance().file("civ").getString("trade-reset.day", "SUNDAY"),
                civ.balance().file("civ").getString("trade-reset.time", "17:00"));
        m.send(sender, "civ.time.trade", Messages.arg("time", trade));
        String revo = until(now, civ.balance().file("civ").getString("revolution.start-day", "TUESDAY"),
                civ.balance().file("civ").getString("revolution.start-time", "18:00"));
        m.send(sender, "civ.time.revolution", Messages.arg("time", revolutionOpen() ? m.plain("civ.time.open") : revo));
        Duration nextHour = Duration.between(now, now.plusHours(1).withMinute(0).withSecond(0).withNano(0));
        m.send(sender, "civ.time.hourly", Messages.arg("time", Durations.format(nextHour)));
    }

    private static String until(ZonedDateTime now, String day, String time) {
        ZonedDateTime next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)))).with(LocalTime.parse(time));
        if (!next.isAfter(now)) next = next.plusWeeks(1);
        return Durations.format(Duration.between(now, next));
    }

    // --- money ----------------------------------------------------------------------------------

    private void deposit(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        long cents = Amounts.require(input);
        Ledger.residentToCiv(resident(p), c, cents);
        civ.messages().send(p, "civ.deposit.done", Messages.money("amount", cents));
    }

    private void withdraw(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        if (gov().isAnarchy(c)) throw new CivException("town.withdraw.anarchy");
        Resident r = resident(p);
        if (input.equalsIgnoreCase("alla")) {
            CivPerms.check(p, c, "waperm");
            long total = 0;
            if (c.treasury() > 0) {
                total += c.treasury();
                Ledger.civToResident(c, r, c.treasury());
            }
            for (Town t : civ.state().towns(c)) {
                if (t.status() == TownStatus.CAPTURED || t.treasury() <= 0) continue;
                long amount = t.treasury();
                Ledger.townToResident(t, r, amount);
                total += amount;
            }
            civ.messages().send(p, "town.withdraw.all", Messages.money("amount", total));
            return;
        }
        CivPerms.check(p, c, "withdraw");
        long cents = input.equalsIgnoreCase("all") ? c.treasury() : Amounts.require(input);
        Ledger.civToResident(c, r, cents);
        civ.messages().send(p, "civ.withdraw.done", Messages.money("amount", cents));
    }

    private void debt(Player p) throws CivException {
        Civilization c = myCiv(p);
        Messages m = civ.messages();
        long total = 0;
        for (Town t : civ.state().towns(c)) {
            if (t.debt() <= 0) continue;
            total += t.debt();
            m.sendRaw(p, "civ.debt.town", Messages.arg("town", t.name()), Messages.money("debt", t.debt()),
                    Messages.arg("since", t.debtSince() == null ? "-" : DATE.format(t.debtSince().atZone(civ.clock().zone()))),
                    Messages.arg("status", m.plain("town.status." + t.status().name().toLowerCase(Locale.ROOT))));
        }
        if (c.debt() > 0) {
            total += c.debt();
            m.sendRaw(p, "civ.debt.civ", Messages.money("debt", c.debt()));
        }
        m.send(p, total > 0 ? "civ.debt.total" : "civ.debt.none", Messages.money("total", total));
    }

    /** Pays the debts of the civ's towns with the chosen status from the player's balance (spec §8.3). */
    private void payDebt(Player p, String status) throws CivException {
        Civilization c = myCiv(p);
        Resident r = resident(p);
        String s = status.toLowerCase(Locale.ROOT);
        if (!List.of("all", "native", "defeated", "bought", "affiliated", "not_defeated").contains(s)) throw new CivException("civ.debt.bad-status");
        long paid = 0;
        for (Town t : civ.state().towns(c)) {
            if (t.debt() <= 0) continue;
            boolean match = switch (s) {
                case "native" -> t.status() == TownStatus.NATIVE;
                case "defeated" -> t.status() == TownStatus.CAPTURED;
                case "bought" -> t.status() == TownStatus.BOUGHT;
                case "affiliated" -> t.status() == TownStatus.AFFILIATED;
                case "not_defeated" -> t.status() != TownStatus.CAPTURED;
                default -> true;
            };
            if (!match) continue;
            long pay = Math.min(t.debt(), r.balance());
            if (pay <= 0) break;
            Ledger.charge(r, pay);
            t.debt(t.debt() - pay);
            civ.state().save(t);
            paid += pay;
        }
        if (s.equals("all") && c.debt() > 0 && r.balance() > 0) {
            long pay = Math.min(c.debt(), r.balance());
            Ledger.charge(r, pay);
            c.debt(c.debt() - pay);
            civ.state().save(c);
            paid += pay;
        }
        if (paid <= 0) throw new CivException("civ.debt.nothing-paid");
        Channels.civ(c, "civ.debt.paid", Messages.arg("name", p.getName()), Messages.money("amount", paid));
    }

    // --- groups & perms -------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> groupCommand() {
        return lit("group")
                .then(lit("add").then(word("player").suggests(CmdKit.players()).then(word("group").suggests(CmdKit.values(List.of("leaders", "advisers")))
                        .executes(Cmd.player((p, ctx) -> group(p, arg(ctx, "player"), arg(ctx, "group"), true))))))
                .then(lit("remove").then(word("player").suggests(CmdKit.players()).then(word("group").suggests(CmdKit.values(List.of("leaders", "advisers")))
                        .executes(Cmd.player((p, ctx) -> group(p, arg(ctx, "player"), arg(ctx, "group"), false))))))
                .then(lit("info").then(word("group").suggests(CmdKit.values(List.of("leaders", "advisers")))
                        .executes(Cmd.player((p, ctx) -> groupInfo(p, arg(ctx, "group"))))));
    }

    /** Leaders are appointed by the owner only, advisers by the owner and leaders (spec §7.3 assumption). */
    private void group(Player p, String name, String group, boolean add) throws CivException {
        Civilization c = myCiv(p);
        boolean leaders = switch (group.toLowerCase(Locale.ROOT)) {
            case "leaders", "leader" -> true;
            case "advisers", "adviser", "advisors" -> false;
            default -> throw new CivException("civ.group.unknown");
        };
        requireRank(p, c, leaders ? Civilization.Rank.OWNER : Civilization.Rank.LEADER);
        Resident target = Lookup.resident(name);
        if (!CivPerms.isMember(c, target.uuid())) throw new CivException("civ.group.not-member", Messages.arg("name", target.name()));
        if (target.uuid().equals(c.owner())) throw new CivException("civ.group.owner");
        Set<UUID> set = leaders ? c.leaders() : c.advisers();
        if (add) {
            if (!set.add(target.uuid())) throw new CivException("civ.group.already", Messages.arg("name", target.name()));
            (leaders ? c.advisers() : c.leaders()).remove(target.uuid());
        } else if (!set.remove(target.uuid())) {
            throw new CivException("civ.group.not-in", Messages.arg("name", target.name()));
        }
        civ.state().save(c);
        Channels.civ(c, add ? "civ.group.added" : "civ.group.removed", Messages.arg("name", target.name()),
                Messages.arg("group", civ.messages().plain(leaders ? "civ.group.leaders" : "civ.group.advisers")), Messages.arg("by", p.getName()));
    }

    private void groupInfo(Player p, String group) throws CivException {
        Civilization c = myCiv(p);
        boolean leaders = group.toLowerCase(Locale.ROOT).startsWith("leader");
        civ.messages().send(p, "civ.group.info", Messages.arg("group", civ.messages().plain(leaders ? "civ.group.leaders" : "civ.group.advisers")),
                Messages.arg("list", names(leaders ? c.leaders() : c.advisers())));
    }

    private LiteralArgumentBuilder<CommandSourceStack> permCommand() {
        return lit("perm").executes(Cmd.player((p, ctx) -> permShow(p)))
                .then(word("perm").suggests(CmdKit.values(CivPerms::keys))
                        .then(word("level").suggests(CmdKit.values(List.of("OWNER", "LEADERS", "ADVISERS", "RESIDENTS", "ALL")))
                                .executes(Cmd.player((p, ctx) -> permSet(p, arg(ctx, "perm"), arg(ctx, "level"))))))
                .then(lit("player").then(word("player").suggests(CmdKit.players()).then(word("perm").suggests(CmdKit.values(CivPerms::keys))
                        .then(word("value").suggests(CmdKit.values(List.of("on", "off")))
                                .executes(Cmd.player((p, ctx) -> permPlayer(p, arg(ctx, "player"), arg(ctx, "perm"), arg(ctx, "value"))))))));
    }

    private void checkPermEditor(Player p, Civilization c) throws CivException {
        if (c.rank(p.getUniqueId()) == Civilization.Rank.OWNER) return;
        Set<UUID> delegated = c.personalPerms().get("perm");
        if (delegated != null && delegated.contains(p.getUniqueId())) return;
        throw new CivException("civ.owner-only");
    }

    private void permShow(Player p) throws CivException {
        Civilization c = myCiv(p);
        Messages m = civ.messages();
        m.send(p, "civ.perm.header");
        for (String key : CivPerms.keys()) {
            Set<UUID> personal = c.personalPerms().getOrDefault(key, Set.of());
            m.sendRaw(p, "civ.perm.entry", Messages.arg("perm", key),
                    Messages.arg("level", m.plain("civ.perm.level." + CivPerms.level(c, key).name().toLowerCase(Locale.ROOT))),
                    Messages.arg("players", personal.isEmpty() ? "" : names(personal)));
        }
    }

    private void permSet(Player p, String perm, String levelName) throws CivException {
        Civilization c = myCiv(p);
        checkPermEditor(p, c);
        if (!CivPerms.keys().contains(perm)) throw new CivException("civ.perm.unknown", Messages.arg("perm", perm));
        CivPerms.Level level = CivPerms.Level.parse(levelName);
        if (level == null) throw new CivException("civ.perm.bad-level");
        if ((level == CivPerms.Level.RESIDENTS || level == CivPerms.Level.ALL) && !civ.balance().file("civ").getStringList("perms-open-levels").contains(perm)) {
            throw new CivException("civ.perm.bad-level");
        }
        CivPerms.set(c, perm, level);
        Channels.civ(c, "civ.perm.changed", Messages.arg("perm", perm),
                Messages.arg("level", civ.messages().plain("civ.perm.level." + level.name().toLowerCase(Locale.ROOT))), Messages.arg("by", p.getName()));
    }

    private void permPlayer(Player p, String name, String perm, String value) throws CivException {
        Civilization c = myCiv(p);
        checkPermEditor(p, c);
        if (!CivPerms.keys().contains(perm)) throw new CivException("civ.perm.unknown", Messages.arg("perm", perm));
        Resident target = Lookup.resident(name);
        if (!CivPerms.isMember(c, target.uuid())) throw new CivException("civ.group.not-member", Messages.arg("name", target.name()));
        boolean on = List.of("on", "yes", "true", "да").contains(value.toLowerCase(Locale.ROOT));
        Set<UUID> set = c.personalPerms().computeIfAbsent(perm, k -> new HashSet<>());
        if (on) set.add(target.uuid());
        else set.remove(target.uuid());
        civ.state().save(c);
        Channels.civ(c, on ? "civ.perm.granted" : "civ.perm.revoked", Messages.arg("perm", perm), Messages.arg("name", target.name()),
                Messages.arg("by", p.getName()));
    }

    // --- /civ set -------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> setCommand() {
        return lit("set").executes(CmdKit.help("civ.set.help"))
                .then(lit("taxes").then(word("percent").executes(Cmd.player((p, ctx) -> setTaxes(p, arg(ctx, "percent"))))))
                .then(lit("science").then(word("percent").executes(Cmd.player((p, ctx) -> setScience(p, arg(ctx, "percent"))))))
                .then(lit("color").executes(Cmd.player((p, ctx) -> setColor(p, null)))
                        .then(word("hex").executes(Cmd.player((p, ctx) -> setColor(p, arg(ctx, "hex"))))))
                .then(lit("ctag").then(word("color").executes(Cmd.player((p, ctx) -> setTagColor(p, arg(ctx, "color"))))))
                .then(lit("moneylog").executes(Cmd.player((p, ctx) -> toggleFlag(p, "moneylog", null))))
                .then(lit("joinlog").executes(Cmd.player((p, ctx) -> toggleFlag(p, "joinlog", "joinlog"))))
                .then(lit("wperm").then(word("level").suggests(CmdKit.values(List.of("OWNERS", "LEADERS", "ADVISERS", "RESIDENTS", "ALL")))
                        .executes(Cmd.player((p, ctx) -> setOwnerPerm(p, "wperm", arg(ctx, "level"))))))
                .then(lit("waperm").then(word("level").suggests(CmdKit.values(List.of("OWNERS", "LEADERS", "ADVISERS")))
                        .executes(Cmd.player((p, ctx) -> setOwnerPerm(p, "waperm", arg(ctx, "level"))))))
                .then(lit("scout").then(word("seconds").executes(Cmd.player((p, ctx) -> setScout(p, arg(ctx, "seconds"), false)))))
                .then(lit("scoutrate").then(word("seconds").executes(Cmd.player((p, ctx) -> setScout(p, arg(ctx, "seconds"), true)))));
    }

    private void setTaxes(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.LEADER);
        double v = Amounts.percent(input);
        double max = gov().maxTax(c);
        if (Double.isNaN(v) || v > max + 1e-9) throw new CivException("civ.set.taxes-range", Messages.arg("max", Format.percent(max)));
        c.taxes(v);
        civ.state().save(c);
        Channels.civ(c, "civ.set.taxes", Messages.arg("value", Format.percent(v)), Messages.arg("by", p.getName()));
    }

    private void setScience(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.LEADER);
        double v = Amounts.percent(input);
        if (Double.isNaN(v)) throw new CivException("civ.set.percent-range");
        c.science(v);
        civ.state().save(c);
        Channels.civ(c, "civ.set.science", Messages.arg("value", Format.percent(v)), Messages.arg("by", p.getName()));
    }

    private void setColor(Player p, String hex) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.LEADER);
        if (hex == null) {
            civ.messages().send(p, "civ.set.color-current", Messages.arg("color", c.cultureColor() == null ? "-" : c.cultureColor()));
            return;
        }
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!h.matches("^[0-9a-fA-F]{6}$")) throw new CivException("civ.set.color-bad");
        c.cultureColor("#" + h.toUpperCase(Locale.ROOT));
        civ.state().save(c);
        civ.messages().send(p, "civ.set.color", Messages.arg("color", c.cultureColor()));
    }

    /** Fixes the tag colour for 1 000 000 (spec §5.5): only colours of reached eras; era 0 colour at the last era. */
    private void setTagColor(Player p, String input) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "ctag");
        int index = com.civcraft.town.TownCommands.parseInt(input, 0, TAG_COLORS.size() - 1);
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        int era = research == null ? 0 : research.era(c);
        boolean allowed = index >= 1 ? index <= era : era >= TAG_COLORS.size() - 1;
        if (!allowed) throw new CivException("civ.set.ctag-locked");
        long cost = civ.balance().coins("core", "civ.tag-color-cost", 1_000_000);
        Ledger.chargeCiv(c, cost);
        c.fixedTagColor(index);
        civ.state().save(c);
        Channels.civ(c, "civ.set.ctag", Messages.arg("color", TAG_COLORS.get(index)), Messages.money("cost", cost));
    }

    private void toggleFlag(Player p, String flag, String perm) throws CivException {
        Civilization c = myCiv(p);
        if (perm == null) requireRank(p, c, Civilization.Rank.OWNER);
        else CivPerms.check(p, c, perm);
        boolean value = !c.flag(flag);
        c.flag(flag, value);
        civ.state().save(c);
        civ.messages().send(p, value ? "civ.set.flag-on" : "civ.set.flag-off", Messages.arg("flag", flag));
    }

    private void setOwnerPerm(Player p, String perm, String levelName) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.OWNER);
        CivPerms.Level level = CivPerms.Level.parse(levelName);
        if (level == null || (perm.equals("waperm") && (level == CivPerms.Level.RESIDENTS || level == CivPerms.Level.ALL))) {
            throw new CivException("civ.perm.bad-level");
        }
        CivPerms.set(c, perm, level);
        civ.messages().send(p, "civ.perm.changed", Messages.arg("perm", perm),
                Messages.arg("level", civ.messages().plain("civ.perm.level." + level.name().toLowerCase(Locale.ROOT))), Messages.arg("by", p.getName()));
    }

    private void setScout(Player p, String input, boolean rate) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.LEADER);
        int v = com.civcraft.town.TownCommands.parseInt(input, civ.balance().getInt("town", "scout-rate.min", 10),
                civ.balance().getInt("town", "scout-rate.max", 60));
        CivData d = module.data(c);
        if (rate) d.scoutRate(v);
        else d.scoutSeconds(v);
        module.save(d);
        civ.messages().send(p, "town.set.scoutrate", Messages.arg("value", v));
    }

    // --- motd -----------------------------------------------------------------------------------

    private void showMotd(Player p) throws CivException {
        Civilization c = myCiv(p);
        List<String> motd = module.data(c).motd();
        if (motd.isEmpty()) throw new CivException("civ.motd.none");
        for (String line : motd) civ.messages().sendRaw(p, "civ.motd.line", Messages.arg("text", line));
    }

    private void motd(Player p, String text, boolean append) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "motd");
        CivData d = module.data(c);
        String clean = text.length() > 200 ? text.substring(0, 200) : text;
        if (!append) d.motd().clear();
        if (!clean.equals("-")) d.motd().add(clean);
        int max = civ.balance().getInt("civ", "motd-lines", 5);
        while (d.motd().size() > max) d.motd().removeFirst();
        module.save(d);
        Channels.civ(c, "civ.motd.changed", Messages.arg("by", p.getName()));
    }

    // --- ownership ------------------------------------------------------------------------------

    private void changeOwner(Player p, String name) throws CivException {
        Civilization c = myCiv(p);
        requireRank(p, c, Civilization.Rank.OWNER);
        Resident target = Lookup.resident(name);
        if (target.uuid().equals(p.getUniqueId())) throw new CivException("civ.changeowner.self");
        if (!CivPerms.isMember(c, target.uuid())) throw new CivException("civ.group.not-member", Messages.arg("name", target.name()));
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof VictoryStatus v && v.victoryCountdownRunning() && v.daysUntilVictory() >= 0
                    && v.daysUntilVictory() <= civ.balance().getInt("civ", "changeowner-victory-lock-days", 3)) {
                throw new CivException("civ.changeowner.victory");
            }
        }
        Messages msg = civ.messages();
        Prompts.confirm(p, msg.component("civ.changeowner.confirm-title"), msg.lines("civ.changeowner.confirm-body", Messages.arg("name", target.name())),
                msg.component("prompt.yes"), msg.component("prompt.no"), player -> {
                    if (!player.getUniqueId().equals(c.owner()) || !CivPerms.isMember(c, target.uuid())) return;
                    transferOwner(c, target.uuid());
                });
    }

    private void transferOwner(Civilization c, UUID newOwner) {
        UUID old = c.owner();
        c.owner(newOwner);
        c.leaders().remove(newOwner);
        c.advisers().remove(newOwner);
        if (old != null) c.leaders().add(old);
        civ.state().save(c);
        Channels.civ(c, "civ.changeowner.done", Messages.arg("name", name(newOwner)));
    }

    /**
     * Take ownership when the owner has been inactive for 7 days (spec §6.6): active leaders first, then
     * advisers, then mayors of the capital, then anybody.
     */
    private void claimOwner(Player p) throws CivException {
        Civilization c = myCiv(p);
        if (p.getUniqueId().equals(c.owner())) throw new CivException("civ.claimowner.already");
        Duration inactive = Duration.ofDays(civ.balance().getInt("core", "civ.inactive-owner-days", 7));
        if (active(c.owner(), inactive)) throw new CivException("civ.claimowner.active", Messages.arg("name", name(c.owner())));
        Town capital = civ.state().capital(c);
        List<Set<UUID>> tiers = List.of(c.leaders(), c.advisers(), capital == null ? Set.of() : capital.mayors());
        for (Set<UUID> tier : tiers) {
            boolean anyActive = tier.stream().anyMatch(id -> active(id, inactive));
            if (!anyActive) continue;
            if (!tier.contains(p.getUniqueId())) throw new CivException("civ.claimowner.priority");
            break;
        }
        transferOwner(c, p.getUniqueId());
    }

    private boolean active(UUID id, Duration inactive) {
        if (id == null) return false;
        if (Bukkit.getPlayer(id) != null) return true;
        Resident r = civ.state().resident(id);
        return r != null && r.lastSeen().plus(inactive).isAfter(Instant.now());
    }

    // --- disband, revolution, mute, warnings ----------------------------------------------------

    private void disbandTown(Player p, String name) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "disbandtown");
        Town town = Lookup.town(name);
        if (!Objects.equals(town.civId(), c.id())) throw new CivException("civ.disbandtown.foreign");
        towns().commands().checkDisbandable(town);
        town.civConfirmedDisband(!town.civConfirmedDisband());
        civ.state().save(town);
        Channels.town(town, town.civConfirmedDisband() ? "town.disband.civ-confirmed" : "town.disband.civ-withdrawn", Messages.arg("name", p.getName()));
        civ.messages().send(p, town.civConfirmedDisband() ? "town.disband.civ-confirmed" : "town.disband.civ-withdrawn", Messages.arg("name", p.getName()));
        towns().commands().startBurningIfConfirmed(town);
    }

    private boolean revolutionOpen() {
        ZonedDateTime now = civ.clock().now();
        DayOfWeek day = DayOfWeek.valueOf(civ.balance().file("civ").getString("revolution.start-day", "TUESDAY").toUpperCase(Locale.ROOT));
        LocalTime time = LocalTime.parse(civ.balance().file("civ").getString("revolution.start-time", "18:00"));
        int hours = civ.balance().getInt("civ", "revolution.duration-hours", 24);
        ZonedDateTime start = now.with(TemporalAdjusters.previousOrSame(day)).with(time);
        if (start.isAfter(now)) start = start.minusWeeks(1);
        return now.isBefore(start.plusHours(hours));
    }

    /** Revolution of a conquered civilization (spec §18.2). */
    private void revolution(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        Resident r = resident(p);
        // Residents of captured towns belong to the conqueror now; the revolution is led by the native civ's leaders.
        Town own = civ.state().townOf(r);
        Civilization home = own == null ? null : civ.state().civ(own.nativeCivId());
        if (home == null) home = c;
        if (home == null) throw new CivException("error.not-in-civ");
        if (!home.isConquered()) throw new CivException("civ.revolution.not-conquered");
        if (!home.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (dip != null && dip.isWarTime()) throw new CivException("diplomacy.war-time");
        if (!revolutionOpen()) throw new CivException("civ.revolution.window");
        List<Town> captured = new ArrayList<>();
        Instant capturedAt = null;
        for (Town t : civ.state().towns()) {
            if (t.status() == TownStatus.CAPTURED && Objects.equals(t.nativeCivId(), home.id())) {
                captured.add(t);
                if (t.capturedAt() != null && (capturedAt == null || t.capturedAt().isBefore(capturedAt))) capturedAt = t.capturedAt();
            }
        }
        if (captured.isEmpty()) throw new CivException("civ.revolution.capitulated");
        int minDays = civ.balance().getInt("civ", "revolution.min-days-after-capture", 3);
        if (capturedAt != null && capturedAt.plus(Duration.ofDays(minDays)).isAfter(Instant.now())) {
            throw new CivException("civ.revolution.too-early", Messages.arg("days", minDays));
        }
        Civilization conqueror = civ.state().civ(home.conqueredBy());
        if (conqueror != null && civ.stats().civ(conqueror).get("revolution_immunity") > 0) throw new CivException("civ.revolution.immune");
        final Civilization rebel = home;
        long cost = revolutionCost(rebel, captured);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("civ.revolution.confirm-title"), m.lines("civ.revolution.confirm-body", Messages.money("cost", cost),
                        Messages.arg("towns", captured.size())), m.component("prompt.yes"), m.component("prompt.no"),
                player -> {
                    try {
                        startRevolution(player, rebel, cost);
                    } catch (CivException e) {
                        m.send(player, e.key(), e.args());
                    }
                });
    }

    private long revolutionCost(Civilization c, List<Town> captured) {
        var s = civ.balance().section("civ", "revolution");
        int towns = (int) captured.stream().filter(t -> !t.id().equals(c.capitalId())).count();
        double score = 0;
        TownModule t = towns();
        for (Town town : captured) score += townScore(t, town);
        return EconomyMath.revolutionCost(Money.ofCoins(s.getDouble("base-cost", 500000)), Money.ofCoins(s.getDouble("per-town", 250000)),
                towns, s.getDouble("score-factor", 0.1) * 100, score, Money.ofCoins(s.getDouble("max-cost", 5000000)));
    }

    private void startRevolution(Player p, Civilization c, long cost) throws CivException {
        if (!c.isConquered() || !c.isLeader(p.getUniqueId()) || !revolutionOpen()) throw new CivException("civ.revolution.stale");
        Ledger.chargeCiv(c, cost);
        TownModule t = towns();
        for (Town town : List.copyOf(civ.state().towns())) {
            if (town.status() != TownStatus.CAPTURED || !Objects.equals(town.nativeCivId(), c.id())) continue;
            TownStatus restore = town.statusBeforeCapture() == null ? TownStatus.NATIVE : town.statusBeforeCapture();
            town.statusBeforeCapture(null);
            t.service().transfer(town, c, restore, false);
        }
        c.conqueredBy(null);
        civ.state().save(c);
        gov().startChange(c, gov().governments().stream().filter(g -> g.id().equals(gov().startId())).findFirst().orElseThrow(), true);
        new RevolutionEvent(c.id()).call();
        Channels.global("civ.revolution.started", Messages.arg("civ", c.name()));
    }

    private void muteList(Player p) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "mute");
        CivData d = module.data(c);
        List<String> towns = new ArrayList<>();
        for (String id : d.mutedTowns()) {
            Town t = civ.state().town(id);
            if (t != null) towns.add(t.name());
        }
        civ.messages().send(p, "civ.mute.list", Messages.arg("players", names(d.mutedPlayers())),
                Messages.arg("towns", towns.isEmpty() ? "-" : String.join(", ", towns)));
    }

    private void muteTown(Player p, String name) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "mute");
        Town t = Lookup.town(name);
        CivData d = module.data(c);
        boolean muted = d.mutedTowns().add(t.id());
        if (!muted) d.mutedTowns().remove(t.id());
        module.save(d);
        Channels.civ(c, muted ? "civ.mute.muted" : "civ.mute.unmuted", Messages.arg("name", t.name()), Messages.arg("by", p.getName()));
    }

    private void mutePlayer(Player p, String name) throws CivException {
        Civilization c = myCiv(p);
        CivPerms.check(p, c, "mute");
        Resident r = Lookup.resident(name);
        if (c.rank(r.uuid()).atLeast(c.rank(p.getUniqueId())) && !r.uuid().equals(p.getUniqueId()) && c.rank(r.uuid()) != Civilization.Rank.NONE) {
            throw new CivException("civ.mute.rank");
        }
        CivData d = module.data(c);
        boolean muted = d.mutedPlayers().add(r.uuid());
        if (!muted) d.mutedPlayers().remove(r.uuid());
        module.save(d);
        Channels.civ(c, muted ? "civ.mute.muted" : "civ.mute.unmuted", Messages.arg("name", r.name()), Messages.arg("by", p.getName()));
    }

    private void warnings(Player p) throws CivException {
        Civilization c = myCiv(p);
        civ.messages().send(p, "civ.warning.show", Messages.arg("count", c.warnings()),
                Messages.arg("limit", civ.balance().getInt("core", "town.warnings-to-disband", 3)));
    }

    private void warn(CommandSender sender, String name, int delta) throws CivException {
        Civilization c = Lookup.civ(name);
        c.warnings(Math.max(0, c.warnings() + delta));
        civ.state().save(c);
        int limit = civ.balance().getInt("core", "town.warnings-to-disband", 3);
        civ.messages().send(sender, "civ.warning.set", Messages.arg("civ", c.name()), Messages.arg("count", c.warnings()));
        Channels.civ(c, "civ.warning.changed", Messages.arg("count", c.warnings()), Messages.arg("limit", limit));
        if (c.warnings() >= limit) {
            Channels.global("civ.warning.disbanded", Messages.arg("civ", c.name()));
            towns().service().deleteCiv(c);
        }
    }

    // --- misc -----------------------------------------------------------------------------------

    private void teleport(Player p) throws CivException {
        Civilization c = myCiv(p);
        Town capital = civ.state().capital(c);
        if (capital == null) throw new CivException("civ.no-capital");
        towns().commands().teleport(p, capital);
    }

    private void tiles(Player p) throws CivException {
        Civilization c = myCiv(p);
        StructureApi api = civ.apiOrNull(StructureApi.class);
        for (Town t : civ.state().towns(c)) {
            civ.messages().sendRaw(p, "civ.tiles.entry", Messages.arg("town", t.name()), Messages.arg("slots", towns().slots(t)),
                    Messages.arg("claims", civ.state().claimCount(t)), Messages.arg("limit", towns().claimLimit(t)),
                    Messages.arg("structures", api == null ? "-" : String.valueOf(api.of(t).size())));
        }
    }

    private void stats(Player p) throws CivException {
        Civilization c = myCiv(p);
        double hammers = 0;
        double beakers = 0;
        double culture = 0;
        double income = 0;
        int residents = 0;
        int claims = 0;
        long treasury = c.treasury();
        for (Town t : civ.state().towns(c)) {
            Production.Figures f = towns().production().figures(t);
            hammers += f.hammers();
            beakers += f.beakers();
            culture += f.culture();
            income += f.income();
            residents += t.residents().size();
            claims += civ.state().claimCount(t);
            treasury += t.treasury();
        }
        civ.messages().sendRaw(p, "civ.stats", Messages.arg("towns", civ.state().towns(c).size()), Messages.arg("residents", residents),
                Messages.arg("claims", claims), Messages.number("hammers", hammers), Messages.number("beakers", beakers),
                Messages.number("culture", culture), Messages.number("income", income), Messages.money("treasury", treasury),
                Messages.number("score", Math.floor(score(c))));
    }

    private void progress(Player p) throws CivException {
        Civilization c = myCiv(p);
        StructureApi api = civ.apiOrNull(StructureApi.class);
        for (Town t : civ.state().towns(c)) {
            List<String> parts = new ArrayList<>();
            if (api != null) {
                for (StructureApi.Placed s : api.of(t)) {
                    if (!s.complete()) parts.add(s.type() + " " + Format.percent(s.progress()));
                }
            }
            for (TownData.Job job : towns().data(t).jobs()) {
                Upgrades.Def def = towns().upgrades().get(job.upgrade());
                parts.add((def == null ? job.upgrade() : def.name()) + " " + Format.percent(job.required() <= 0 ? 1 : job.progress() / job.required()));
            }
            civ.messages().sendRaw(p, "civ.progress.entry", Messages.arg("town", t.name()),
                    Messages.arg("list", parts.isEmpty() ? "-" : String.join(", ", parts)));
        }
    }

    private void sort(Player p, String field) throws CivException {
        Civilization c = myCiv(p);
        TownModule t = towns();
        ToDoubleFunction<Town> key = switch (field.toLowerCase(Locale.ROOT)) {
            case "money" -> town -> town.treasury();
            case "hammers" -> t::hammersPerHour;
            case "beakers" -> t::beakersPerHour;
            case "growth" -> t::growth;
            case "culturerate" -> t::culturePerHour;
            case "happiness" -> t::happinessPercent;
            case "tax" -> town -> town.flatTax();
            case "culture" -> Town::culture;
            default -> town -> townScore(t, town);
        };
        List<Town> list = new ArrayList<>(civ.state().towns(c));
        list.sort(Comparator.comparingDouble(key).reversed());
        int i = 1;
        for (Town town : list) {
            civ.messages().sendRaw(p, "civ.sort.entry", Messages.arg("place", i++), Messages.arg("town", town.name()),
                    Messages.number("value", key.applyAsDouble(town) / (field.equalsIgnoreCase("money") || field.equalsIgnoreCase("tax") ? 100.0 : 1)));
        }
    }

    private void scouts(Player p) throws CivException {
        Resident r = resident(p);
        boolean hidden = !r.setting("hide-scout");
        r.setting("hide-scout", hidden);
        civ.state().save(r);
        civ.messages().send(p, hidden ? "civ.scouts.off" : "civ.scouts.on");
    }
}
