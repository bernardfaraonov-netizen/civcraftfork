package com.civcraft.town;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.civ.CivPerms;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.economy.Amounts;
import com.civcraft.economy.Ledger;
import com.civcraft.government.GovernmentModule;
import com.civcraft.gui.Items;
import com.civcraft.gui.PagedMenu;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.plot.PlotModule;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.resident.ResidentModule;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownData.Job;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** The {@code /town} ({@code /t}) command tree (spec §19.2) and its handlers. */
public final class TownCommands {

    private final CivCraft civ;
    private final TownModule module;
    private final TownInfoCommands info;
    private final TownGroupCommands groups;

    TownCommands(CivCraft civ, TownModule module) {
        this.civ = civ;
        this.module = module;
        this.info = new TownInfoCommands(civ, module);
        this.groups = new TownGroupCommands(civ, module);
    }

    void register() {
        CmdKit.register(civ, this::tree, "Town commands", List.of("t"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        LiteralArgumentBuilder<CommandSourceStack> root = lit("town")
                .executes(CmdKit.help("town.help"))
                .then(lit("help").executes(CmdKit.help("town.help")))
                .then(info.infoCommand())
                .then(info.showCommand())
                .then(lit("list").executes(Cmd.run(ctx -> info.list(ctx.getSource().getSender()))))
                .then(lit("top5").executes(Cmd.run(ctx -> info.top(ctx.getSource().getSender(), 5))))
                .then(lit("survey").executes(Cmd.player((p, ctx) -> info.survey(p, 1)))
                        .then(word("level").executes(Cmd.player((p, ctx) -> info.survey(p, parseInt(arg(ctx, "level"), 1, 11))))))
                .then(lit("happycalc").then(word("happiness").then(word("unhappiness")
                        .executes(Cmd.run(ctx -> info.happycalc(ctx.getSource().getSender(), arg(ctx, "happiness"), arg(ctx, "unhappiness")))))))
                .then(lit("members").executes(Cmd.player((p, ctx) -> info.members(p, null)))
                        .then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> info.members(p, arg(ctx, "town"))))))
                .then(lit("location").executes(Cmd.player((p, ctx) -> info.location(p))))
                .then(lit("select").then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> select(p, arg(ctx, "town"))))))
                .then(lit("add").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> invite(p, arg(ctx, "player"))))))
                .then(evict("evict"))
                .then(evict("kick"))
                .then(lit("leave").executes(Cmd.player((p, ctx) -> leave(p))))
                .then(lit("claim").executes(Cmd.player((p, ctx) -> claim(p, null)))
                        .then(word("group").executes(Cmd.player((p, ctx) -> claim(p, arg(ctx, "group"))))))
                .then(lit("unclaim").executes(Cmd.player((p, ctx) -> unclaim(p))))
                .then(groups.groupCommand())
                .then(groups.leaveGroupCommand())
                .then(groups.outlawCommand())
                .then(setCommand())
                .then(upgradeCommand())
                .then(lit("deposit").then(word("amount").executes(Cmd.player((p, ctx) -> deposit(p, arg(ctx, "amount"))))))
                .then(lit("withdraw").then(word("amount").executes(Cmd.player((p, ctx) -> withdraw(p, arg(ctx, "amount"))))))
                .then(lit("claimmayor").executes(Cmd.player((p, ctx) -> claimMayor(p))))
                .then(lit("disbandtown").executes(Cmd.player((p, ctx) -> disband(p))))
                .then(lit("capitulate").executes(Cmd.player((p, ctx) -> capitulate(p))))
                .then(changeTown("changetown"))
                .then(changeTown("change"))
                .then(teleport("teleport"))
                .then(teleport("tp"))
                .then(lit("chammers").executes(Cmd.player((p, ctx) -> chammers(p))))
                .then(lit("warning")
                        .then(lit("show").executes(Cmd.player((p, ctx) -> info.warnings(p))))
                        .then(lit("add").requires(Cmd.perm("civcraft.admin")).then(word("town").suggests(CmdKit.towns())
                                .executes(Cmd.run(ctx -> warn(ctx.getSource().getSender(), arg(ctx, "town"), 1)))))
                        .then(lit("remove").requires(Cmd.perm("civcraft.admin")).then(word("town").suggests(CmdKit.towns())
                                .executes(Cmd.run(ctx -> warn(ctx.getSource().getSender(), arg(ctx, "town"), -1))))))
                .then(lit("motd").executes(Cmd.player((p, ctx) -> info.motd(p)))
                        .then(CmdKit.text("text").executes(Cmd.player((p, ctx) -> setMotd(p, arg(ctx, "text"))))));
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof TownCommandExtension ext) ext.extendTown(root);
        }
        return root;
    }

    public static int parseInt(String s, int min, int max) throws CivException {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) throw new CivException("error.invalid-number", Messages.arg("min", min), Messages.arg("max", max));
            return v;
        } catch (NumberFormatException e) {
            throw new CivException("error.invalid-number", Messages.arg("min", min), Messages.arg("max", max));
        }
    }

    private Resident resident(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        if (r == null) throw new CivException("error.internal");
        return r;
    }

    private DiplomacyApi dip() {
        return civ.apiOrNull(DiplomacyApi.class);
    }

    private boolean warTime() {
        DiplomacyApi d = dip();
        return d != null && d.isWarTime();
    }

    // --- select / invite / evict / leave --------------------------------------------------------

    private void select(Player p, String name) throws CivException {
        Town town = Lookup.town(name);
        if (!module.canSelect(p, town)) throw new CivException("town.select.denied", Messages.arg("town", town.name()));
        Resident r = resident(p);
        r.selectedTownId(town.id());
        civ.state().save(r);
        civ.messages().send(p, "town.select.done", Messages.arg("town", town.name()));
    }

    private void invite(Player p, String name) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        if (!town.isOfficial(p.getUniqueId())) CivPerms.check(p, c, "add");
        Resident target = Lookup.resident(name);
        if (target.hasTown()) throw new CivException("town.add.has-town", Messages.arg("name", target.name()));
        if (target.campId() != null) throw new CivException("town.add.in-camp", Messages.arg("name", target.name()));
        Player tp = Bukkit.getPlayer(target.uuid());
        if (tp == null) throw new CivException("town.add.offline", Messages.arg("name", target.name()));
        checkAddWindow(c);
        module.service().checkRejoinCooldown(target, town);
        ResidentModule rm = civ.module(ResidentModule.class);
        Duration ttl = Duration.ofSeconds(civ.balance().getInt("town", "invite-seconds", 300));
        rm.requests().ask("town-invite", "town-invite:" + target.uuid() + ":" + town.id(), List.of(target.uuid()), p.getUniqueId(),
                civ.messages().component("town.add.question", Messages.arg("town", town.name()), Messages.arg("name", p.getName())),
                ttl, responder -> {
                    Town t = civ.state().town(town.id());
                    if (t == null) throw new CivException("error.unknown-town", Messages.arg("name", town.name()));
                    checkAddWindow(civ.state().civOf(t));
                    module.service().addResident(t, civ.state().resident(responder));
                    civ.messages().send(responder, "town.add.joined", Messages.arg("town", t.name()));
                }, null);
        civ.messages().send(p, "town.add.sent", Messages.arg("name", target.name()));
    }

    /** /t add is locked from N days before the war window when the civ is at war (spec §6.5 assumption). */
    private void checkAddWindow(Civilization c) throws CivException {
        DiplomacyApi d = dip();
        if (d == null || c == null) return;
        if (d.victoryRunning()) return;
        boolean atWar = d.isAtWar(c.id());
        if (!atWar && civ.balance().file("town").getBoolean("add-lock-only-if-at-war", true)) return;
        if (d.isWarWithin(Duration.ofDays(civ.balance().getInt("town", "add-lock-days-before-war", 3)))) {
            throw new CivException("town.add.war-lock");
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> evict(String name) {
        return lit(name).then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> evict(p, arg(ctx, "player")))));
    }

    private void evict(Player p, String name) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        if (!town.isOfficial(p.getUniqueId())) CivPerms.check(p, c, "kick");
        Resident target = Lookup.resident(name);
        if (!town.isResident(target.uuid())) throw new CivException("town.evict.not-resident", Messages.arg("name", target.name()));
        if (target.uuid().equals(p.getUniqueId())) throw new CivException("town.evict.self");
        int mine = CivPerms.position(c, town, p.getUniqueId());
        int theirs = CivPerms.position(c, town, target.uuid());
        if (theirs > 0 && mine < theirs) throw new CivException("town.evict.rank", Messages.arg("name", target.name()));
        if (town.isMayor(target.uuid()) && town.mayors().size() <= 1) throw new CivException("town.evict.last-mayor");
        if (c != null && target.uuid().equals(c.owner())) throw new CivException("town.evict.owner");
        module.service().removeResident(town, target, true, p.getName(), true);
        civ.messages().send(p, "town.evict.done", Messages.arg("name", target.name()));
    }

    private void leave(Player p) throws CivException {
        Resident r = resident(p);
        Town town = civ.state().townOf(r);
        if (town == null) throw new CivException("error.not-in-town");
        if (town.isMayor(p.getUniqueId()) && town.mayors().size() <= 1) throw new CivException("town.leave.last-mayor");
        Civilization c = civ.state().civOf(town);
        if (c != null && p.getUniqueId().equals(c.owner())) throw new CivException("town.leave.owner");
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("town.leave.confirm-title"), m.lines("town.leave.confirm-body", Messages.arg("town", town.name())),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    Resident res = civ.state().resident(player);
                    Town t = civ.state().townOf(res);
                    if (t == null || !t.id().equals(town.id())) return;
                    if (t.isMayor(player.getUniqueId()) && t.mayors().size() <= 1) {
                        m.send(player, "town.leave.last-mayor");
                        return;
                    }
                    module.service().removeResident(t, res, false, null, true);
                    m.send(player, "town.leave.done", Messages.arg("town", t.name()));
                });
    }

    // --- claims ---------------------------------------------------------------------------------

    private void claim(Player p, String group) throws CivException {
        Town town = module.selectedTown(p);
        module.requireManage(p, town);
        if (!civ.settings().isGameWorld(p.getWorld())) throw new CivException("town.claim.world");
        ChunkKey chunk = ChunkKey.of(p.getLocation());
        Claim existing = civ.state().claim(chunk);
        if (existing != null) {
            Town owner = civ.state().town(existing.townId());
            throw new CivException("town.claim.taken", Messages.arg("town", owner == null ? "?" : owner.name()));
        }
        if (!civ.culture().inCulture(town, chunk)) throw new CivException("town.claim.not-culture", Messages.arg("town", town.name()));
        if (module.claimsLocked()) throw new CivException("town.claim.war-lock");
        int count = civ.state().claimCount(town);
        int limit = module.claimLimit(town);
        if (count >= limit) throw new CivException("town.claim.limit", Messages.arg("limit", limit));
        String groupName = null;
        if (group != null) {
            groupName = group.toLowerCase(Locale.ROOT);
            if (!town.groups().containsKey(groupName)) throw new CivException("town.group.unknown", Messages.arg("group", group));
        }
        long price = module.nextClaimPrice(town);
        Ledger.chargeTown(town, price);
        Claim claim = new Claim(chunk, town.id());
        if (groupName != null) {
            claim.groups().clear();
            claim.groups().add(groupName);
        }
        civ.state().addClaim(claim);
        PlotModule plots = civ.apiOrNull(PlotModule.class);
        if (plots != null) plots.value(claim, price);
        civ.messages().send(p, "town.claim.done", Messages.arg("x", chunk.x()), Messages.arg("z", chunk.z()),
                Messages.money("price", price), Messages.arg("count", count + 1), Messages.arg("limit", limit));
    }

    private void unclaim(Player p) throws CivException {
        ChunkKey chunk = ChunkKey.of(p.getLocation());
        Claim claim = civ.state().claim(chunk);
        if (claim == null) throw new CivException("town.unclaim.none");
        // Rights are checked against the town that owns the chunk, not the selected one (audit C-31).
        Town town = civ.state().town(claim.townId());
        if (town == null) throw new CivException("town.unclaim.none");
        module.requireManage(p, town);
        if (claim.locked()) throw new CivException("town.unclaim.locked");
        if (module.claimsLocked()) throw new CivException("town.claim.war-lock");
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api != null) {
            for (StructureApi.Placed s : api.of(town)) {
                com.civcraft.core.util.Cuboid box = com.civcraft.core.util.Cuboid.of(s.origin(), s.sizeX(), s.sizeY(), s.sizeZ());
                if (box.chunks().contains(chunk)) throw new CivException("town.unclaim.structure", Messages.arg("type", s.type()));
            }
        }
        if (civ.state().claimCount(town) <= 1) throw new CivException("town.unclaim.last");
        civ.state().removeClaim(claim);
        PlotModule plots = civ.apiOrNull(PlotModule.class);
        if (plots != null) plots.forget(claim);
        civ.messages().send(p, "town.unclaim.done", Messages.arg("x", chunk.x()), Messages.arg("z", chunk.z()));
    }

    // --- /t set ---------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> setCommand() {
        LiteralArgumentBuilder<CommandSourceStack> set = lit("set").executes(CmdKit.help("town.set.help"))
                .then(lit("taxrate").then(word("percent").executes(Cmd.player((p, ctx) -> setTaxRate(p, arg(ctx, "percent"))))))
                .then(lit("flattax").then(word("amount").executes(Cmd.player((p, ctx) -> setFlatTax(p, arg(ctx, "amount"))))))
                .then(lit("scoutrate").then(word("seconds").executes(Cmd.player((p, ctx) -> setScoutRate(p, arg(ctx, "seconds"))))));
        for (String fee : civ.balance().section("town", "fees").getKeys(false)) {
            set.then(lit(fee + "fee").then(word("percent").executes(Cmd.player((p, ctx) -> setFee(p, fee, arg(ctx, "percent"))))));
        }
        return set;
    }

    /** /t set is for mayors, assistants (assumption) and civ leaders (spec §7.5). */
    private Town settable(Player p) throws CivException {
        Town town = module.selectedTown(p);
        module.requireManage(p, town);
        return town;
    }

    private void setTaxRate(Player p, String input) throws CivException {
        Town town = settable(p);
        double rate = Amounts.percent(input);
        double max = civ.balance().getDouble("town", "max-tax-rate", 1.0);
        if (Double.isNaN(rate) || rate > max) throw new CivException("town.set.range", Messages.arg("min", 0), Messages.arg("max", Format.number(max * 100)));
        town.taxRate(rate);
        civ.state().save(town);
        Channels.town(town, "town.set.taxrate", Messages.arg("value", Format.percent(rate)), Messages.arg("by", p.getName()));
    }

    private void setFlatTax(Player p, String input) throws CivException {
        Town town = settable(p);
        long cents;
        if (input.equals("0")) cents = 0;
        else cents = Amounts.require(input);
        long max = civ.balance().coins("town", "max-flat-tax", 10000);
        if (cents > max) throw new CivException("town.set.flattax-max", Messages.money("max", max));
        town.flatTax(cents);
        civ.state().save(town);
        Channels.town(town, "town.set.flattax", Messages.money("value", cents), Messages.arg("by", p.getName()));
    }

    private void setFee(Player p, String fee, String input) throws CivException {
        Town town = settable(p);
        double min = civ.balance().getDouble("town", "fees." + fee + ".min", 0);
        double max = civ.balance().getDouble("town", "fees." + fee + ".max", 0.15);
        double rate = Amounts.percent(input);
        if (Double.isNaN(rate) || rate < min - 1e-9 || rate > max + 1e-9) {
            throw new CivException("town.set.range", Messages.arg("min", Format.number(min * 100)), Messages.arg("max", Format.number(max * 100)));
        }
        town.setFee(fee, rate);
        civ.state().save(town);
        Channels.town(town, "town.set.fee", Messages.arg("fee", fee), Messages.arg("value", Format.percent(rate)), Messages.arg("by", p.getName()));
    }

    private void setScoutRate(Player p, String input) throws CivException {
        Town town = settable(p);
        int v = parseInt(input, civ.balance().getInt("town", "scout-rate.min", 10), civ.balance().getInt("town", "scout-rate.max", 60));
        module.data(town).scoutRate(v);
        module.saveData(town);
        civ.messages().send(p, "town.set.scoutrate", Messages.arg("value", v));
    }

    private void setMotd(Player p, String text) throws CivException {
        Town town = module.selectedTown(p);
        if (!town.isMayor(p.getUniqueId())) throw new CivException("town.mayor-only");
        String clean = text.length() > 200 ? text.substring(0, 200) : text;
        module.data(town).motd(clean.equals("-") ? null : clean);
        module.saveData(town);
        Channels.town(town, "town.motd.set", Messages.arg("text", clean));
    }

    // --- upgrades -------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> upgradeCommand() {
        return lit("upgrade").executes(Cmd.player((p, ctx) -> listUpgrades(p)))
                .then(lit("list").executes(Cmd.player((p, ctx) -> listUpgrades(p))))
                .then(lit("purchased").executes(Cmd.player((p, ctx) -> purchased(p))))
                .then(lit("progress").executes(Cmd.player((p, ctx) -> progress(p))))
                .then(lit("buy").then(CmdKit.text("upgrade").suggests(CmdKit.values(() -> module.upgrades().all().stream().map(Upgrades.Def::name).toList()))
                        .executes(Cmd.player((p, ctx) -> buyUpgrade(p, arg(ctx, "upgrade"))))))
                .then(lit("cancel").then(CmdKit.text("upgrade").executes(Cmd.player((p, ctx) -> cancelUpgrade(p, arg(ctx, "upgrade"))))));
    }

    private long upgradeCost(Town town, Upgrades.Def def) {
        return Math.max(0, Money.multiply(def.cost(), civ.stats().town(town).apply(com.civcraft.effect.Stats.UPGRADE_COST, 1.0)));
    }

    private boolean available(Town town, Upgrades.Def def) {
        TownData d = module.data(town);
        if (d.upgrades().contains(def.id())) return false;
        for (Job j : d.jobs()) if (j.upgrade().equals(def.id())) return false;
        int level = Upgrades.townLevel(def.id());
        if (level > 0 && level <= town.level()) return false;
        if (def.requires() == null || d.upgrades().contains(def.requires())) return true;
        int required = Upgrades.townLevel(def.requires());
        return required > 0 && town.level() >= required;
    }

    /** Tech and research-tree gates of an upgrade (spec §6.3; tech ids of balance/techs.yml). */
    private boolean techOk(Civilization c, Upgrades.Def def) {
        if (!module.hasTech(c, def.tech())) return false;
        com.civcraft.science.ResearchApi research = civ.apiOrNull(com.civcraft.science.ResearchApi.class);
        return research == null || c == null || research.unlocked(c, "upgrades", def.id());
    }

    private void listUpgrades(Player p) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        Messages m = civ.messages();
        m.sendRaw(p, "town.upgrade.list-header", Messages.arg("town", town.name()));
        int shown = 0;
        for (Upgrades.Def def : module.upgrades().all()) {
            if (!available(town, def)) continue;
            boolean tech = techOk(c, def);
            m.sendRaw(p, tech ? "town.upgrade.entry" : "town.upgrade.entry-locked", Messages.arg("upgrade", def.name()),
                    Messages.money("cost", upgradeCost(town, def)), Messages.number("hammers", def.hammers()),
                    Messages.arg("tech", def.tech() == null ? "-" : module.techName(def.tech())));
            shown++;
        }
        if (shown == 0) m.sendRaw(p, "town.upgrade.none");
    }

    private void purchased(Player p) throws CivException {
        Town town = module.selectedTown(p);
        List<String> names = new ArrayList<>();
        for (String id : module.data(town).upgrades()) {
            Upgrades.Def def = module.upgrades().get(id);
            names.add(def == null ? id : def.name());
        }
        civ.messages().send(p, "town.upgrade.purchased", Messages.arg("list", names.isEmpty() ? "-" : String.join(", ", names)));
    }

    private void progress(Player p) throws CivException {
        Town town = module.selectedTown(p);
        TownData d = module.data(town);
        if (d.jobs().isEmpty()) throw new CivException("town.upgrade.no-jobs");
        double rate = module.hammersPerHour(town) / Math.max(1, d.jobs().size());
        for (Job job : d.jobs()) {
            Upgrades.Def def = module.upgrades().get(job.upgrade());
            double left = job.required() - job.progress();
            String eta = rate <= 0 ? "∞" : Durations.format(Duration.ofSeconds((long) (left / rate * 3600)));
            civ.messages().sendRaw(p, "town.upgrade.progress", Messages.arg("upgrade", def == null ? job.upgrade() : def.name()),
                    Messages.arg("bar", Format.progressBar(job.required() <= 0 ? 1 : job.progress() / job.required(), 20)),
                    Messages.number("done", job.progress()), Messages.number("required", job.required()), Messages.arg("eta", eta));
        }
    }

    private void buyUpgrade(Player p, String input) throws CivException {
        Town town = module.selectedTown(p);
        module.requireManage(p, town);
        Upgrades.Def def = Lookup.option(input, module.upgrades().all(), Upgrades.Def::id, Upgrades.Def::name);
        if (def == null) throw new CivException("town.upgrade.unknown", Messages.arg("name", input));
        if (!available(town, def)) throw new CivException("town.upgrade.unavailable", Messages.arg("upgrade", def.name()));
        Civilization c = civ.state().civOf(town);
        if (!techOk(c, def)) throw new CivException("town.upgrade.tech", Messages.arg("tech", def.tech() == null ? "-" : module.techName(def.tech())));
        if (town.inDebt()) throw new CivException("town.debt-blocked");
        long cost = upgradeCost(town, def);
        if (cost > 0) Ledger.chargeTown(town, cost);
        TownData d = module.data(town);
        if (def.hammers() <= 0) {
            d.upgrades().add(def.id());
            module.upgrades().complete(town, def);
            Channels.town(town, "town.upgrade.complete", Messages.arg("upgrade", def.name()));
        } else {
            d.jobs().add(new Job(def.id(), def.hammers(), cost));
            Channels.town(town, "town.upgrade.bought", Messages.arg("upgrade", def.name()), Messages.money("cost", cost),
                    Messages.number("hammers", def.hammers()), Messages.arg("by", p.getName()));
        }
        module.saveData(town);
    }

    private void cancelUpgrade(Player p, String input) throws CivException {
        Town town = module.selectedTown(p);
        module.requireManage(p, town);
        Upgrades.Def def = Lookup.option(input, module.upgrades().all(), Upgrades.Def::id, Upgrades.Def::name);
        TownData d = module.data(town);
        boolean removed = def != null && d.jobs().removeIf(j -> j.upgrade().equals(def.id()));
        if (!removed) throw new CivException("town.upgrade.not-in-progress", Messages.arg("name", input));
        module.saveData(town);
        Channels.town(town, "town.upgrade.cancelled", Messages.arg("upgrade", def.name()), Messages.arg("by", p.getName()));
    }

    // --- money ----------------------------------------------------------------------------------

    private void deposit(Player p, String input) throws CivException {
        Town town = module.selectedTown(p);
        Resident r = resident(p);
        if (!town.isResident(p.getUniqueId()) && !module.canManage(p, town)) throw new CivException("town.deposit.not-resident");
        long cents = Amounts.require(input);
        Ledger.residentToTown(r, town, cents);
        if (town.debt() > 0 && civ.balance().file("town").getBoolean("deposit-repays-debt", true)) {
            long repay = Ledger.takeUpTo(town, town.debt());
            town.debt(town.debt() - repay);
            civ.state().save(town);
            if (repay > 0) civ.messages().send(p, "town.deposit.repaid", Messages.money("amount", repay), Messages.money("debt", town.debt()));
        }
        civ.messages().send(p, "town.deposit.done", Messages.money("amount", cents), Messages.arg("town", town.name()));
    }

    private void withdraw(Player p, String input) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        GovernmentModule gov = civ.module(GovernmentModule.class);
        if (c != null && gov.isAnarchy(c)) throw new CivException("town.withdraw.anarchy");
        Resident r = resident(p);
        if (input.equalsIgnoreCase("alla")) {
            CivPerms.check(p, c, "waperm");
            long total = 0;
            for (Town t : civ.state().towns(c)) {
                if (t.status() == TownStatus.CAPTURED || t.treasury() <= 0) continue;
                long amount = t.treasury();
                Ledger.townToResident(t, r, amount);
                total += amount;
            }
            civ.messages().send(p, "town.withdraw.all", Messages.money("amount", total));
            return;
        }
        if (!town.isMayor(p.getUniqueId())) CivPerms.check(p, c, "withdraw");
        long cents = input.equalsIgnoreCase("all") ? town.treasury() : Amounts.require(input);
        Ledger.townToResident(town, r, cents);
        civ.messages().send(p, "town.withdraw.done", Messages.money("amount", cents), Messages.arg("town", town.name()));
    }

    // --- leadership, disband, capitulation -------------------------------------------------------

    private void claimMayor(Player p) throws CivException {
        Resident r = resident(p);
        Town town = civ.state().townOf(r);
        if (town == null) throw new CivException("error.not-in-town");
        if (town.isMayor(p.getUniqueId())) throw new CivException("town.claimmayor.already");
        Duration inactive = Duration.ofDays(civ.balance().getInt("core", "town.inactive-mayor-days", 7));
        for (UUID mayor : town.mayors()) {
            Resident m = civ.state().resident(mayor);
            if (m == null) continue;
            if (Bukkit.getPlayer(mayor) != null || m.lastSeen().plus(inactive).isAfter(Instant.now())) {
                throw new CivException("town.claimmayor.active", Messages.arg("name", m.name()));
            }
        }
        town.mayors().add(p.getUniqueId());
        civ.state().save(town);
        Channels.town(town, "town.claimmayor.done", Messages.arg("name", p.getName()));
    }

    private void disband(Player p) throws CivException {
        Town town = module.selectedTown(p);
        if (!town.isMayor(p.getUniqueId())) throw new CivException("town.mayor-only");
        checkDisbandable(town);
        if (town.mayorConfirmedDisband()) {
            town.mayorConfirmedDisband(false);
            civ.state().save(town);
            Channels.town(town, "town.disband.mayor-withdrawn", Messages.arg("name", p.getName()));
            return;
        }
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("town.disband.confirm-title"), m.lines("town.disband.confirm-body", Messages.arg("town", town.name())),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    try {
                        checkDisbandable(town);
                    } catch (CivException e) {
                        m.send(player, e.key(), e.args());
                        return;
                    }
                    if (!town.isMayor(player.getUniqueId())) return;
                    town.mayorConfirmedDisband(true);
                    civ.state().save(town);
                    Channels.town(town, "town.disband.mayor-confirmed", Messages.arg("name", player.getName()));
                    startBurningIfConfirmed(town);
                });
    }

    /** Rules shared by /t disbandtown and /civ disbandtown (spec §6.9, §18.1, audit C-8, C-20). */
    public void checkDisbandable(Town town) throws CivException {
        Civilization c = civ.state().civOf(town);
        if (town.disbanding()) throw new CivException("town.disband.already");
        if (c != null && town.id().equals(c.capitalId())) throw new CivException("town.disband.capital");
        if (town.isCaptured() && !civ.balance().file("town").getBoolean("captured-can-disband", false)) {
            throw new CivException("town.disband.captured");
        }
        if (warTime()) throw new CivException("town.disband.war");
    }

    public void startBurningIfConfirmed(Town town) {
        boolean civOk = town.civConfirmedDisband() || town.isCaptured();
        if (!town.mayorConfirmedDisband() || !civOk || town.disbanding()) return;
        town.disbanding(true);
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.global("town.disband.started", Messages.arg("town", town.name()));
    }

    private void capitulate(Player p) throws CivException {
        Town town = module.selectedTown(p);
        if (!town.isMayor(p.getUniqueId())) throw new CivException("town.mayor-only");
        if (!town.isCaptured()) throw new CivException("town.capitulate.not-captured");
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("town.capitulate.confirm-title"), m.lines("town.capitulate.confirm-body", Messages.arg("town", town.name())),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    if (!town.isCaptured() || !town.isMayor(player.getUniqueId())) return;
                    module.capitulate(town);
                });
    }

    // --- change town ----------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> changeTown(String name) {
        return lit(name).then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> changeTown(p, arg(ctx, "town")))));
    }

    private void changeTown(Player p, String targetName) throws CivException {
        Resident r = resident(p);
        Town from = civ.state().townOf(r);
        if (from == null) throw new CivException("error.not-in-town");
        Town to = Lookup.town(targetName);
        validateChange(p, r, from, to);
        long cost = civ.balance().coins("core", "town.change-town-cost", 50000);
        Civilization c = civ.state().civOf(from);
        com.civcraft.resident.Requests.Action action = responder -> {
            Resident res = civ.state().resident(p.getUniqueId());
            Town f = civ.state().townOf(res);
            Town t = civ.state().town(to.id());
            if (f == null || t == null) throw new CivException("town.change.stale");
            validateChange(p, res, f, t);
            Ledger.charge(res, cost);
            module.service().removeResident(f, res, false, null, false);
            res.leftCiv().remove(t.civId());
            module.service().addResident(t, res);
            Player moved = Bukkit.getPlayer(res.uuid());
            if (moved != null) civ.messages().send(moved, "town.change.done", Messages.arg("town", t.name()), Messages.money("cost", cost));
        };
        if (c != null && c.isLeader(p.getUniqueId())) {
            action.run(p);
            return;
        }
        List<UUID> leaders = new ArrayList<>();
        if (c != null) {
            leaders.add(c.owner());
            leaders.addAll(c.leaders());
        }
        leaders.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (leaders.isEmpty()) throw new CivException("town.change.no-leaders");
        civ.module(ResidentModule.class).requests().ask("town-change", "town-change:" + p.getUniqueId(), leaders, p.getUniqueId(),
                civ.messages().component("town.change.question", Messages.arg("name", p.getName()),
                        Messages.arg("from", from.name()), Messages.arg("to", to.name())),
                Duration.ofSeconds(civ.balance().getInt("town", "request-seconds", 300)), action, null);
        civ.messages().send(p, "town.change.sent", Messages.money("cost", cost));
    }

    private void validateChange(Player p, Resident r, Town from, Town to) throws CivException {
        if (from.id().equals(to.id())) throw new CivException("town.change.same");
        if (!Objects.equals(from.civId(), to.civId())) throw new CivException("town.change.other-civ");
        if (from.residents().size() <= 1) throw new CivException("town.change.last-resident");
        if (from.isMayor(p.getUniqueId()) && from.mayors().size() <= 1) throw new CivException("town.leave.last-mayor");
        if (warTime()) throw new CivException("town.change.war");
        long cost = civ.balance().coins("core", "town.change-town-cost", 50000);
        if (!r.has(cost)) throw new CivException("error.not-enough-money", Messages.money("amount", cost));
    }

    // --- teleport -------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> teleport(String name) {
        return lit(name).executes(Cmd.player((p, ctx) -> teleportMenu(p)))
                .then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> teleport(p, Lookup.town(arg(ctx, "town"))))));
    }

    private void teleportMenu(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        new PagedMenu<Town>(civ.messages().component("town.teleport.menu-title")) {
            @Override
            protected List<Town> entries(Player viewer) {
                List<Town> result = new ArrayList<>();
                for (Town t : civ.state().towns()) {
                    try {
                        checkTeleportTarget(viewer, t);
                        result.add(t);
                    } catch (CivException ignored) {
                        // not a valid destination
                    }
                }
                return result;
            }

            @Override
            protected ItemStack icon(Player viewer, Town t) {
                return Items.of(Material.ENDER_PEARL).name(Component.text(t.name()))
                        .lore(civ.messages().component("town.teleport.menu-lore",
                                Messages.money("cost", civ.balance().coins("core", "town.teleport-cost", 5000)))).build();
            }

            @Override
            protected Consumer<InventoryClickEvent> action(Player viewer, Town t) {
                return e -> {
                    viewer.closeInventory();
                    try {
                        teleport(viewer, t);
                    } catch (CivException ex) {
                        civ.messages().send(viewer, ex.key(), ex.args());
                    }
                };
            }
        }.open(p);
    }

    /** Destination rules of /t teleport (spec §6.7). */
    public void checkTeleportTarget(Player p, Town target) throws CivException {
        Civilization mine = civ.state().civOf(p);
        if (mine == null) throw new CivException("error.not-in-civ");
        boolean own = Objects.equals(target.civId(), mine.id());
        boolean war = warTime();
        if (own && target.isCaptured()) throw new CivException("town.teleport.captured");
        if (!own) {
            boolean lostToEnemy = war && Objects.equals(target.nativeCivId(), mine.id()) && target.isCaptured();
            if (!lostToEnemy) throw new CivException("town.teleport.foreign");
        }
        if (!module.service().mainBuildingComplete(target)) throw new CivException("town.teleport.building");
        if (target.center() == null || target.center().bukkitWorld() == null) throw new CivException("town.teleport.building");
        Town myTown = civ.state().townOf(p);
        if (war && myTown != null && myTown.isCaptured()) throw new CivException("town.teleport.captured-resident");
    }

    public void teleport(Player p, Town target) throws CivException {
        checkTeleportTarget(p, target);
        List<String> forbidden = civ.balance().file("town").getStringList("teleport-forbidden-worlds");
        if (forbidden.contains(p.getWorld().getName())) throw new CivException("town.teleport.world");
        ResidentModule rm = civ.module(ResidentModule.class);
        Duration cooldown = Duration.ofSeconds(civ.balance().getInt("core", "town.teleport-cooldown-seconds", 300));
        rm.checkCooldown(p, ResidentModule.COOLDOWN_TOWN_TP, cooldown);
        long cost = civ.balance().coins("core", "town.teleport-cost", 5000);
        Resident r = resident(p);
        if (!r.has(cost)) throw new CivException("error.not-enough-money", Messages.money("amount", cost));
        Runnable start = () -> rm.teleports().start(p, civ.balance().getInt("core", "town.teleport-warmup-seconds", 10), player -> {
            try {
                checkTeleportTarget(player, target);
                rm.checkCooldown(player, ResidentModule.COOLDOWN_TOWN_TP, cooldown);
                Ledger.charge(civ.state().resident(player), cost);
            } catch (CivException e) {
                civ.messages().send(player, e.key(), e.args());
                return null;
            }
            rm.markCooldown(player, ResidentModule.COOLDOWN_TOWN_TP);
            civ.messages().send(player, "town.teleport.done", Messages.arg("town", target.name()), Messages.money("cost", cost));
            return safeTop(target);
        }, null);
        if (r.setting("tpconf")) {
            start.run();
            return;
        }
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("town.teleport.confirm-title", Messages.arg("town", target.name())),
                m.lines("town.teleport.confirm-body", Messages.money("cost", cost),
                        Messages.arg("seconds", civ.balance().getInt("core", "town.teleport-warmup-seconds", 10))),
                m.component("prompt.yes"), m.component("prompt.no"), player -> start.run());
    }

    static Location safeTop(Town town) {
        Location c = town.center().center();
        int y = c.getWorld().getHighestBlockYAt(c.getBlockX(), c.getBlockZ()) + 1;
        return new Location(c.getWorld(), c.getX(), y, c.getZ());
    }

    // --- chammers & warnings --------------------------------------------------------------------

    private void chammers(Player p) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        if (!town.isMayor(p.getUniqueId())) CivPerms.check(p, c, "chammers");
        if (town.convertingHammers()) throw new CivException("town.chammers.active",
                Messages.arg("time", Durations.format(Duration.between(Instant.now(), town.chammersUntil()))));
        int hours = civ.balance().getInt("core", "town.chammers-hours", 24);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("town.chammers.confirm-title"), m.lines("town.chammers.confirm-body", Messages.arg("hours", hours)),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    if (town.convertingHammers()) return;
                    town.chammersUntil(Instant.now().plus(Duration.ofHours(hours)));
                    civ.state().save(town);
                    module.production().invalidate();
                    Channels.town(town, "town.chammers.started", Messages.arg("hours", hours), Messages.arg("by", player.getName()));
                });
    }

    private void warn(org.bukkit.command.CommandSender sender, String name, int delta) throws CivException {
        Town town = Lookup.town(name);
        town.warnings(Math.max(0, town.warnings() + delta));
        civ.state().save(town);
        int limit = civ.balance().getInt("core", "town.warnings-to-disband", 3);
        civ.messages().send(sender, "town.warning.set", Messages.arg("town", town.name()), Messages.arg("count", town.warnings()));
        Channels.town(town, "town.warning.changed", Messages.arg("count", town.warnings()), Messages.arg("limit", limit));
        if (town.warnings() >= limit) {
            Civilization c = civ.state().civOf(town);
            if (c != null && town.id().equals(c.capitalId())) module.service().deleteCiv(c);
            else module.service().delete(town, "town.warning.disbanded");
        }
    }
}
