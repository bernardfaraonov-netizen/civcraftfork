package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Money;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /civ research ...} (spec 01 §19.1, spec 03 §1). */
final class ResearchCommands {

    private ResearchCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> build(CivCraft civ, ScienceModule science) {
        Function<Player, List<String>> availableNames = p -> {
            Civilization c = civ.state().civOf(p);
            List<String> names = new ArrayList<>();
            if (c == null) return names;
            for (TechTree.Tech t : science.available(c)) names.add(t.name());
            return names;
        };
        return Cmd.literal("research")
                .executes(Cmd.player((p, ctx) -> new TechTreeMenu(science, "military").open(p)))
                .then(Cmd.literal("gui").executes(Cmd.player((p, ctx) -> new TechTreeMenu(science, "military").open(p))))
                .then(Cmd.literal("help").executes(Cmd.run(ctx -> civ.messages().sendRaw(sender(ctx), "science.help"))))
                .then(Cmd.literal("on").then(techArg(civ, science, availableNames)
                        .executes(Cmd.player((p, ctx) -> on(civ, science, p, tech(science, ctx))))))
                .then(Cmd.literal("change").then(techArg(civ, science, availableNames)
                        .executes(Cmd.player((p, ctx) -> change(civ, science, p, tech(science, ctx))))))
                .then(Cmd.literal("queue")
                        .executes(Cmd.player((p, ctx) -> showQueue(civ, science, p)))
                        .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> showQueue(civ, science, p))))
                        .then(Cmd.literal("add").then(techArg(civ, science, p -> allNames(science))
                                .executes(Cmd.player((p, ctx) -> queueAdd(civ, science, p, tech(science, ctx))))))
                        .then(Cmd.literal("cancel")
                                .executes(Cmd.player((p, ctx) -> queueRemove(civ, science, p, null)))
                                .then(techArg(civ, science, p -> queuedNames(civ, science, p))
                                        .executes(Cmd.player((p, ctx) -> queueRemove(civ, science, p, tech(science, ctx))))))
                        .then(Cmd.literal("remove").then(techArg(civ, science, p -> queuedNames(civ, science, p))
                                .executes(Cmd.player((p, ctx) -> queueRemove(civ, science, p, tech(science, ctx)))))))
                .then(Cmd.literal("progress").executes(Cmd.player((p, ctx) -> progress(civ, science, p))))
                .then(Cmd.literal("finished").executes(Cmd.player((p, ctx) -> finished(civ, science, p))))
                .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> available(civ, science, p))))
                .then(Cmd.literal("available").executes(Cmd.player((p, ctx) -> available(civ, science, p))))
                .then(Cmd.literal("calc").then(techArg(civ, science, p -> allNames(science))
                        .executes(Cmd.player((p, ctx) -> calc(civ, science, p, tech(science, ctx))))))
                .then(Cmd.literal("era").executes(Cmd.player((p, ctx) -> era(civ, science, p))))
                .then(Cmd.literal("taxes").executes(Cmd.player((p, ctx) -> taxes(civ, science, p))))
                .then(Cmd.literal("admin").requires(Cmd.perm("civcraft.admin"))
                        .then(Cmd.literal("give").then(civArg(civ).then(techArg(civ, science, p -> allNames(science))
                                .executes(Cmd.run(ctx -> adminGive(civ, science, ctx, true))))))
                        .then(Cmd.literal("take").then(civArg(civ).then(techArg(civ, science, p -> allNames(science))
                                .executes(Cmd.run(ctx -> adminGive(civ, science, ctx, false))))))
                        .then(Cmd.literal("beakers").then(civArg(civ)
                                .then(Cmd.arg("amount", DoubleArgumentType.doubleArg(0, 1e9))
                                        .executes(Cmd.run(ctx -> adminBeakers(civ, science, ctx)))))));
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> techArg(
            CivCraft civ, ScienceModule science, Function<Player, List<String>> names) {
        return Cmd.arg("tech", StringArgumentType.greedyString()).suggests((ctx, builder) -> {
            List<String> values = ctx.getSource().getExecutor() instanceof Player p ? names.apply(p) : allNames(science);
            String remaining = builder.getRemainingLowerCase();
            for (String v : values) if (v.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) builder.suggest(v);
            return builder.buildFuture();
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> civArg(CivCraft civ) {
        return Cmd.arg("civ", StringArgumentType.word()).suggests(Cmd.suggest(() ->
                civ.state().civs().stream().map(Civilization::name).toList()));
    }

    private static List<String> allNames(ScienceModule science) {
        return science.tree().all().stream().map(TechTree.Tech::name).toList();
    }

    private static List<String> queuedNames(CivCraft civ, ScienceModule science, Player p) {
        Civilization c = civ.state().civOf(p);
        if (c == null) return List.of();
        return science.state(c).queue().stream().map(science::techName).toList();
    }

    static TechTree.Tech tech(ScienceModule science, CommandContext<CommandSourceStack> ctx) throws CivException {
        String input = StringArgumentType.getString(ctx, "tech");
        TechTree.Tech t = science.tree().find(input);
        if (t == null) throw new CivException("science.error.unknown-tech", Messages.arg("tech", input));
        return t;
    }

    static void on(CivCraft civ, ScienceModule science, Player p, TechTree.Tech t) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RESEARCH);
        String current = science.state(c).current();
        if (current != null) {
            throw new CivException("science.error.busy", Messages.arg("tech", science.techName(current)));
        }
        science.start(c, t);
    }

    static void change(CivCraft civ, ScienceModule science, Player p, TechTree.Tech t) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RESEARCH);
        CivPerms.requireRank(p, c, Civilization.Rank.LEADER);
        science.change(c, t);
    }

    static void queueAdd(CivCraft civ, ScienceModule science, Player p, TechTree.Tech t) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RESEARCH);
        science.queueAdd(c, t);
    }

    static void queueRemove(CivCraft civ, ScienceModule science, Player p, TechTree.Tech t) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        CivPerms.check(civ, p, c, CivPerms.RESEARCH);
        if (!science.queueRemove(c, t)) throw new CivException("science.error.not-queued");
        civ.messages().send(p, t == null ? "science.queue-cleared" : "science.queue-removed",
                Messages.arg("tech", t == null ? "" : t.name()));
    }

    private static void showQueue(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        ResearchState st = science.state(c);
        if (st.queue().isEmpty()) {
            civ.messages().send(p, "science.queue-empty");
            return;
        }
        civ.messages().send(p, "science.queue-header");
        int i = 1;
        for (String id : st.queue()) {
            TechTree.Tech t = science.tree().get(id);
            if (t == null) continue;
            ScienceModule.Cost cost = science.cost(c, t);
            civ.messages().sendRaw(p, "science.queue-line", Messages.arg("n", i++), Messages.arg("tech", t.name()),
                    Messages.money("coins", cost.coinsCents()), Messages.number("beakers", cost.beakers()));
        }
    }

    static void progress(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        ResearchState st = science.state(c);
        civ.messages().send(p, "science.progress-header", Messages.arg("civ", c.name()),
                Messages.number("rate", st.civRate()), Messages.number("stored", st.storedBeakers()));
        TechTree.Tech t = science.tree().get(st.current());
        if (t == null) {
            civ.messages().sendRaw(p, "science.progress-none");
        } else {
            double cost = science.cost(c, t).beakers();
            double done = st.progress(t.id());
            civ.messages().sendRaw(p, "science.progress-line", Messages.arg("tech", t.name()),
                    Messages.number("done", done), Messages.number("cost", cost),
                    Messages.arg("percent", Format.percent(cost <= 0 ? 1 : done / cost)),
                    Messages.arg("bar", civ.messages().parse(Format.progressBar(cost <= 0 ? 1 : done / cost, 30))),
                    Messages.arg("eta", science.formatHours(science.hoursFor(c, cost - done))));
        }
        if (!st.queue().isEmpty()) {
            civ.messages().sendRaw(p, "science.progress-queue", Messages.arg("techs",
                    String.join(", ", st.queue().stream().map(science::techName).toList())));
        }
    }

    private static void finished(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        List<String> done = science.state(c).completed();
        civ.messages().send(p, "science.finished-header", Messages.arg("count", done.size()),
                Messages.arg("total", science.tree().all().size()));
        if (!done.isEmpty()) {
            civ.messages().sendRaw(p, "science.finished-line", Messages.arg("techs",
                    String.join(", ", done.stream().map(science::techName).toList())));
        }
    }

    private static void available(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        List<TechTree.Tech> list = science.available(c);
        if (list.isEmpty()) {
            civ.messages().send(p, "science.available-none");
            return;
        }
        civ.messages().send(p, "science.available-header");
        for (TechTree.Tech t : list) {
            ScienceModule.Cost cost = science.cost(c, t);
            civ.messages().sendRaw(p, "science.available-line", Messages.arg("tech", t.name()),
                    Messages.arg("era", Component.text(science.eraName(t.era()), science.tree().era(t.era()).color())),
                    Messages.money("coins", cost.coinsCents()), Messages.number("beakers", cost.beakers()));
        }
    }

    private static void calc(CivCraft civ, ScienceModule science, Player p, TechTree.Tech t) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        ScienceModule.Cost cost = science.cost(c, t);
        double remaining = Math.max(0, cost.beakers() - science.state(c).progress(t.id()));
        civ.messages().send(p, "science.calc", Messages.arg("tech", t.name()),
                Messages.arg("era", Component.text(science.eraName(t.era()), science.tree().era(t.era()).color())),
                Messages.money("coins", cost.coinsCents()), Messages.number("beakers", cost.beakers()),
                Messages.arg("coin_discount", Format.signedPercent(cost.coinDiscount())),
                Messages.arg("beaker_discount", Format.signedPercent(cost.beakerDiscount())),
                Messages.arg("eta", science.formatHours(science.hoursFor(c, remaining))),
                Messages.arg("requires", t.requires().isEmpty() ? "—"
                        : String.join(", ", t.requires().stream().map(science::techName).toList())));
    }

    private static void era(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        int era = science.era(c);
        int leader = science.leaderEra();
        TechTree.CostFormula f = science.tree().formula();
        int lag = leader - era;
        double discount = lag >= f.eraLagStart() ? f.eraLagPerEra() * (lag - 1) : 0;
        civ.messages().send(p, "science.era", Messages.arg("era", Component.text(science.eraName(era),
                        science.tree().era(era).color())),
                Messages.arg("leader", Component.text(science.eraName(leader), science.tree().era(leader).color())),
                Messages.arg("discount", Format.percent(discount)),
                Messages.arg("hp", science.capitolControlBlockHp(c)));
    }

    private static void taxes(CivCraft civ, ScienceModule science, Player p) throws CivException {
        Civilization c = CivPerms.civOf(civ, p);
        ResearchState st = science.state(c);
        long lastCoins = st.taxCoinsLastHour().values().stream().mapToLong(Long::longValue).sum();
        double lastBeakers = st.taxBeakersLastHour().values().stream().mapToDouble(Double::doubleValue).sum();
        double basePrice = civ.balance().getDouble("core", "civ.beaker-price", 17.5);
        double price = civ.stats().civ(c, Stats.BEAKER_PRICE, basePrice);
        civ.messages().send(p, "science.taxes", Messages.arg("taxes", Format.percent(c.taxes())),
                Messages.arg("science", Format.percent(c.science())), Messages.number("price", price),
                Messages.arg("last_coins", Money.format(lastCoins)), Messages.number("last_beakers", lastBeakers),
                Messages.arg("total_coins", Money.format(st.taxCoinsTotal())),
                Messages.number("total_beakers", st.taxBeakersTotal()));
    }

    private static Civilization civByArg(CivCraft civ, CommandContext<CommandSourceStack> ctx) throws CivException {
        String name = StringArgumentType.getString(ctx, "civ");
        Civilization c = civ.state().civByName(name);
        if (c == null) throw new CivException("error.unknown-civ", Messages.arg("name", name));
        return c;
    }

    private static void adminGive(CivCraft civ, ScienceModule science, CommandContext<CommandSourceStack> ctx,
                                  boolean give) throws CivException {
        Civilization c = civByArg(civ, ctx);
        TechTree.Tech t = tech(science, ctx);
        if (give) science.grant(c, t);
        else if (!science.revoke(c, t)) throw new CivException("science.error.not-researched", Messages.arg("tech", t.name()));
        civ.messages().send(sender(ctx), give ? "science.admin-given" : "science.admin-taken",
                Messages.arg("civ", c.name()), Messages.arg("tech", t.name()));
    }

    private static void adminBeakers(CivCraft civ, ScienceModule science, CommandContext<CommandSourceStack> ctx)
            throws CivException {
        Civilization c = civByArg(civ, ctx);
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        science.addBeakers(c, amount);
        civ.messages().send(sender(ctx), "science.admin-beakers", Messages.arg("civ", c.name()),
                Messages.number("amount", amount));
    }
}
