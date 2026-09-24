package com.civcraft.civ;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Money;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.economy.Ledger;
import com.civcraft.economy.TownValuation;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.town.TownModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * The market of towns and civilizations in debt (spec §8.7). A town is for sale when its debt is older
 * than N days; a civilization when its capital is. The price is the debt plus half the value of all
 * structures; the debt part pays the debt off, the rest is burned (documented decision, audit A-M30).
 * All wonders of the bought object are destroyed and bought towns get the status Bought.
 */
public final class Market {

    private final CivCraft civ;
    private final CivModule module;

    Market(CivCraft civ, CivModule module) {
        this.civ = civ;
        this.module = module;
    }

    private Duration debtDays() {
        return Duration.ofDays(civ.balance().getInt("core", "town.debt-market-days", 7));
    }

    public boolean townForSale(Town town) {
        return town.debt() > 0 && town.debtSince() != null && town.debtSince().plus(debtDays()).isBefore(Instant.now());
    }

    public boolean civForSale(Civilization c) {
        Town capital = civ.state().capital(c);
        return capital != null && townForSale(capital);
    }

    private long structuresValue(Town town) {
        long value = 0;
        com.civcraft.structure.StructureModule sm = civ.apiOrNull(com.civcraft.structure.StructureModule.class);
        if (sm != null) {
            for (com.civcraft.structure.Structure s : sm.structures(town)) {
                if (s.complete()) value = Math.addExact(value, Math.max(0, sm.price(town, s.typeDef())));
            }
        }
        for (com.civcraft.Module m : civ.modules()) if (m instanceof TownValuation v) value += Math.max(0, v.structuresValue(town));
        return value;
    }

    public long price(Town town) {
        return Math.addExact(town.debt(), Money.multiply(structuresValue(town), civ.balance().getDouble("civ", "market.structure-share", 0.5)));
    }

    public long price(Civilization c) {
        long total = 0;
        for (Town t : civ.state().towns(c)) total = Math.addExact(total, price(t));
        return total;
    }

    LiteralArgumentBuilder<CommandSourceStack> command() {
        return lit("market").executes(Cmd.player((p, ctx) -> list(p)))
                .then(lit("list").executes(Cmd.player((p, ctx) -> list(p))))
                .then(lit("buy")
                        .then(lit("town").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> buyTown(p, arg(ctx, "town"))))))
                        .then(lit("towns").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> buyTown(p, arg(ctx, "town"))))))
                        .then(lit("civ").then(word("civ").suggests(CmdKit.civs()).executes(Cmd.player((p, ctx) -> buyCiv(p, arg(ctx, "civ"))))))
                        .then(lit("civs").then(word("civ").suggests(CmdKit.civs()).executes(Cmd.player((p, ctx) -> buyCiv(p, arg(ctx, "civ")))))));
    }

    private Civilization buyer(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        if (!c.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
        if (c.isProvince()) throw new CivException("civ.market.province");
        if (civForSale(c)) throw new CivException("civ.market.self-for-sale");
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (dip != null) {
            if (dip.isWarTime()) throw new CivException("diplomacy.war-time");
            if (!dip.victoryRunning() && dip.isWarWithin(Duration.ofDays(civ.balance().getInt("civ", "market.lock-days-before-war", 3)))) {
                throw new CivException("civ.market.war-lock");
            }
        }
        return c;
    }

    private void list(Player p) {
        Messages m = civ.messages();
        List<String> towns = new ArrayList<>();
        for (Town t : civ.state().towns()) {
            Civilization c = civ.state().civOf(t);
            if (townForSale(t) && (c == null || !t.id().equals(c.capitalId()))) {
                towns.add(m.plain("civ.market.entry", Messages.arg("name", t.name()), Messages.money("price", price(t))));
            }
        }
        List<String> civs = new ArrayList<>();
        for (Civilization c : civ.state().civs()) {
            if (civForSale(c)) civs.add(m.plain("civ.market.entry", Messages.arg("name", c.name()), Messages.money("price", price(c))));
        }
        m.send(p, "civ.market.towns", Messages.arg("list", towns.isEmpty() ? "-" : String.join(", ", towns)));
        m.send(p, "civ.market.civs", Messages.arg("list", civs.isEmpty() ? "-" : String.join(", ", civs)));
    }

    private void buyTown(Player p, String name) throws CivException {
        Civilization buyer = buyer(p);
        Town town = Lookup.town(name);
        Civilization seller = civ.state().civOf(town);
        if (seller != null && seller.id().equals(buyer.id())) throw new CivException("civ.market.own");
        if (!townForSale(town)) throw new CivException("civ.market.not-for-sale", Messages.arg("name", town.name()));
        if (seller != null && town.id().equals(seller.capitalId())) throw new CivException("civ.market.capital");
        int minAge = civ.balance().getInt("civ", "market.town-min-age-days", 7);
        if (town.founded().plus(Duration.ofDays(minAge)).isAfter(Instant.now())) throw new CivException("civ.market.too-young", Messages.arg("days", minAge));
        long price = price(town);
        Ledger.chargeCiv(buyer, price);
        town.debt(0);
        civ.module(TownModule.class).service().transfer(town, buyer, TownStatus.BOUGHT, true);
        Channels.global("civ.market.town-bought", Messages.arg("town", town.name()), Messages.arg("civ", buyer.name()),
                Messages.money("price", price));
    }

    private void buyCiv(Player p, String name) throws CivException {
        Civilization buyer = buyer(p);
        Civilization seller = Lookup.civ(name);
        if (seller.id().equals(buyer.id())) throw new CivException("civ.market.own");
        if (!civForSale(seller)) throw new CivException("civ.market.not-for-sale", Messages.arg("name", seller.name()));
        int minAge = civ.balance().getInt("civ", "market.civ-min-age-days", 14);
        for (Civilization c : List.of(buyer, seller)) {
            if (c.founded().plus(Duration.ofDays(minAge)).isAfter(Instant.now())) throw new CivException("civ.market.too-young", Messages.arg("days", minAge));
        }
        long price = price(seller);
        Ledger.chargeCiv(buyer, price);
        String sellerName = seller.name();
        TownModule towns = civ.module(TownModule.class);
        for (Town t : List.copyOf(civ.state().towns(seller))) {
            t.debt(0);
            towns.service().transfer(t, buyer, TownStatus.BOUGHT, true);
        }
        Civilization still = civ.state().civ(seller.id());
        if (still != null) towns.service().deleteCiv(still);
        Channels.global("civ.market.civ-bought", Messages.arg("civ", sellerName), Messages.arg("buyer", buyer.name()),
                Messages.money("price", price));
    }
}
