package com.civcraft.camp;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.chat.ChatModule;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.economy.Ledger;
import com.civcraft.model.Camp;
import com.civcraft.model.Resident;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.resident.ResidentModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** The {@code /camp} command tree (spec §19.5). */
final class CampCommands {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final CivCraft civ;
    private final CampModule module;

    CampCommands(CivCraft civ, CampModule module) {
        this.civ = civ;
        this.module = module;
    }

    void register() {
        CmdKit.register(civ, this::tree, "Camp commands", List.of());
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        return lit("camp").executes(CmdKit.help("camp.help"))
                .then(lit("help").executes(CmdKit.help("camp.help")))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p))))
                .then(lit("members").executes(Cmd.player((p, ctx) -> members(p))))
                .then(lit("add").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> add(p, arg(ctx, "player"))))))
                .then(lit("remove").then(word("player").executes(Cmd.player((p, ctx) -> remove(p, arg(ctx, "player"))))))
                .then(lit("leave").executes(Cmd.player((p, ctx) -> leave(p))))
                .then(lit("setowner").then(word("player").executes(Cmd.player((p, ctx) -> setOwner(p, arg(ctx, "player"))))))
                .then(lit("upgrade").executes(Cmd.player((p, ctx) -> upgrades(p)))
                        .then(lit("list").executes(Cmd.player((p, ctx) -> upgrades(p))))
                        .then(lit("purchased").executes(Cmd.player((p, ctx) -> purchased(p))))
                        .then(lit("buy").then(CmdKit.text("upgrade").suggests(CmdKit.values(List.of(CampModule.GARDEN, CampModule.CRUSHER, CampModule.LONGHOUSE)))
                                .executes(Cmd.player((p, ctx) -> buy(p, arg(ctx, "upgrade")))))))
                .then(lit("teleport").executes(Cmd.player((p, ctx) -> teleport(p))))
                .then(lit("tp").executes(Cmd.player((p, ctx) -> teleport(p))))
                .then(lit("refresh").executes(Cmd.player((p, ctx) -> refresh(p))))
                .then(lit("undo").executes(Cmd.player((p, ctx) -> undo(p))))
                .then(lit("disband").executes(Cmd.player((p, ctx) -> disband(p))))
                .then(lit("chat").executes(Cmd.player((p, ctx) -> civ.module(ChatModule.class).toggle(p, ChatModule.Channel.CAMP)))
                        .then(CmdKit.text("message").executes(Cmd.player((p, ctx) ->
                                civ.module(ChatModule.class).send(p, ChatModule.Channel.CAMP, arg(ctx, "message"))))))
                .then(lit("location").executes(Cmd.player((p, ctx) -> location(p))))
                .then(lit("list").executes(Cmd.run(ctx -> list(ctx.getSource().getSender()))))
                .then(lit("motd").executes(Cmd.player((p, ctx) -> showMotd(p)))
                        .then(CmdKit.text("text").executes(Cmd.player((p, ctx) -> setMotd(p, arg(ctx, "text"))))));
    }

    private Camp mine(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        Camp camp = r == null ? null : civ.state().camp(r.campId());
        if (camp == null) throw new CivException("camp.not-member");
        return camp;
    }

    private Camp owned(Player p) throws CivException {
        Camp camp = mine(p);
        if (!camp.owner().equals(p.getUniqueId())) throw new CivException("camp.owner-only");
        return camp;
    }

    private String name(UUID id) {
        Resident r = civ.state().resident(id);
        return r == null ? "?" : r.name();
    }

    private void info(Player p) throws CivException {
        Camp camp = mine(p);
        Messages m = civ.messages();
        CampLayout l = module.layout(camp);
        m.sendRaw(p, "camp.info.header", Messages.arg("camp", camp.name()), Messages.arg("tag", camp.tag() == null ? "-" : camp.tag()));
        m.sendRaw(p, "camp.info.owner", Messages.arg("owner", name(camp.owner())), Messages.arg("members", camp.members().size()));
        m.sendRaw(p, "camp.info.hp", Messages.arg("hp", (int) Math.ceil(camp.hp())), Messages.arg("max", module.maxHp()));
        boolean open = module.raidOpen(camp, Instant.now());
        m.sendRaw(p, open ? "camp.info.raid-open" : "camp.info.raid-next",
                Messages.arg("time", TIME.format(module.nextRaid(camp))));
        if (l != null) {
            int coal = module.coal(l);
            int per = civ.balance().getInt("camp", "coal.per-hour", 4);
            m.sendRaw(p, "camp.info.coal", Messages.arg("coal", coal), Messages.arg("hours", per <= 0 ? 0 : coal / per));
        }
        List<String> ups = new ArrayList<>();
        for (String u : camp.upgrades()) ups.add(m.plain("camp.upgrade.name." + u));
        m.sendRaw(p, "camp.info.upgrades", Messages.arg("list", ups.isEmpty() ? "-" : String.join(", ", ups)));
        if (camp.upgrades().contains(CampModule.LONGHOUSE)) {
            m.sendRaw(p, "camp.info.longhouse", Messages.arg("level", camp.longhouseLevel()), Messages.arg("progress", camp.longhouseProgress()),
                    Messages.arg("tokens", camp.leadershipTokens()));
        }
        m.sendRaw(p, "camp.info.founded", Messages.arg("date", TIME.format(camp.founded().atZone(civ.clock().zone()))));
        if (camp.motd() != null) m.sendRaw(p, "camp.info.motd", Messages.arg("text", camp.motd()));
    }

    private void members(Player p) throws CivException {
        Camp camp = mine(p);
        Component list = Component.empty();
        int i = 0;
        for (UUID id : camp.members()) {
            if (i++ > 0) list = list.append(Component.text(", "));
            list = list.append(civ.messages().component(Bukkit.getPlayer(id) != null ? "town.members.online" : "town.members.offline",
                    Messages.arg("name", name(id))));
        }
        civ.messages().send(p, "camp.members", Messages.arg("camp", camp.name()), Messages.arg("count", camp.members().size()));
        p.sendMessage(list);
    }

    private void add(Player p, String targetName) throws CivException {
        Camp camp = owned(p);
        Resident target = Lookup.resident(targetName);
        if (target.hasTown()) throw new CivException("camp.add.in-town", Messages.arg("name", target.name()));
        if (target.campId() != null) throw new CivException("camp.add.in-camp", Messages.arg("name", target.name()));
        if (Bukkit.getPlayer(target.uuid()) == null) throw new CivException("town.add.offline", Messages.arg("name", target.name()));
        int max = civ.balance().getInt("camp", "max-members", 20);
        if (camp.members().size() >= max) throw new CivException("camp.add.full", Messages.arg("max", max));
        civ.module(ResidentModule.class).requests().ask("camp-invite", "camp-invite:" + target.uuid(), List.of(target.uuid()), p.getUniqueId(),
                civ.messages().component("camp.add.question", Messages.arg("camp", camp.name()), Messages.arg("name", p.getName())),
                Duration.ofSeconds(civ.balance().getInt("camp", "invite-seconds", 300)), responder -> {
                    Camp c = civ.state().camp(camp.id());
                    Resident r = civ.state().resident(responder);
                    if (c == null) throw new CivException("camp.unknown", Messages.arg("name", camp.name()));
                    if (r.hasTown()) throw new CivException("camp.add.in-town", Messages.arg("name", r.name()));
                    if (r.campId() != null) throw new CivException("camp.add.in-camp", Messages.arg("name", r.name()));
                    c.members().add(r.uuid());
                    r.campId(c.id());
                    civ.state().save(c);
                    civ.state().save(r);
                    Channels.camp(c, "camp.add.joined", Messages.arg("name", r.name()));
                }, null);
        civ.messages().send(p, "town.add.sent", Messages.arg("name", target.name()));
    }

    private void remove(Player p, String targetName) throws CivException {
        Camp camp = owned(p);
        Resident target = Lookup.resident(targetName);
        if (target.uuid().equals(p.getUniqueId())) throw new CivException("camp.remove.self");
        if (!camp.members().remove(target.uuid())) throw new CivException("camp.remove.not-member", Messages.arg("name", target.name()));
        target.campId(null);
        civ.state().save(target);
        civ.state().save(camp);
        Channels.camp(camp, "camp.remove.done", Messages.arg("name", target.name()));
        Channels.resident(target, "camp.remove.you", Messages.arg("camp", camp.name()));
    }

    private void leave(Player p) throws CivException {
        Camp camp = mine(p);
        if (camp.owner().equals(p.getUniqueId())) throw new CivException("camp.leave.owner");
        Resident r = civ.state().resident(p);
        camp.members().remove(r.uuid());
        r.campId(null);
        civ.state().save(r);
        civ.state().save(camp);
        civ.messages().send(p, "camp.leave.done", Messages.arg("camp", camp.name()));
        Channels.camp(camp, "camp.left", Messages.arg("name", r.name()));
    }

    private void setOwner(Player p, String targetName) throws CivException {
        Camp camp = owned(p);
        Resident target = Lookup.resident(targetName);
        if (!camp.members().contains(target.uuid())) throw new CivException("camp.remove.not-member", Messages.arg("name", target.name()));
        if (target.uuid().equals(p.getUniqueId())) throw new CivException("camp.setowner.self");
        camp.owner(target.uuid());
        civ.state().save(camp);
        Channels.camp(camp, "camp.setowner.done", Messages.arg("name", target.name()));
    }

    // --- upgrades -------------------------------------------------------------------------------

    private ConfigurationSection upgradeDefs() {
        return civ.balance().section("camp", "upgrades");
    }

    private void upgrades(Player p) throws CivException {
        Camp camp = mine(p);
        Messages m = civ.messages();
        m.sendRaw(p, "camp.upgrade.header");
        for (String id : upgradeDefs().getKeys(false)) {
            boolean has = camp.upgrades().contains(id);
            m.sendRaw(p, has ? "camp.upgrade.owned" : "camp.upgrade.entry", Messages.arg("name", m.plain("camp.upgrade.name." + id)),
                    Messages.arg("id", id), Messages.money("cost", civ.balance().coins("camp", "upgrades." + id + ".cost", 0)));
        }
    }

    private void purchased(Player p) throws CivException {
        Camp camp = mine(p);
        List<String> names = new ArrayList<>();
        for (String u : camp.upgrades()) names.add(civ.messages().plain("camp.upgrade.name." + u));
        civ.messages().send(p, "camp.upgrade.purchased", Messages.arg("list", names.isEmpty() ? "-" : String.join(", ", names)));
    }

    private void buy(Player p, String input) throws CivException {
        Camp camp = owned(p);
        String id = null;
        for (String key : upgradeDefs().getKeys(false)) {
            String display = civ.messages().plain("camp.upgrade.name." + key);
            if (key.equalsIgnoreCase(input.trim()) || display.equalsIgnoreCase(input.trim())
                    || display.toLowerCase(Locale.ROOT).startsWith(input.trim().toLowerCase(Locale.ROOT))) {
                id = key;
                break;
            }
        }
        if (id == null) throw new CivException("camp.upgrade.unknown", Messages.arg("name", input));
        if (camp.upgrades().contains(id)) throw new CivException("camp.upgrade.already");
        long cost = civ.balance().coins("camp", "upgrades." + id + ".cost", 0);
        Resident owner = civ.state().resident(p);
        if (cost > 0) Ledger.charge(owner, cost);
        camp.upgrades().add(id);
        civ.state().save(camp);
        CampLayout l = module.layout(camp);
        if (l != null) module.applyMarkers(l, false);
        Channels.camp(camp, "camp.upgrade.bought", Messages.arg("name", civ.messages().plain("camp.upgrade.name." + id)), Messages.money("cost", cost));
    }

    // --- teleport, refresh, undo, disband -------------------------------------------------------

    private void teleport(Player p) throws CivException {
        Camp camp = mine(p);
        ResidentModule rm = civ.module(ResidentModule.class);
        Duration cooldown = Duration.ofMinutes(civ.balance().getInt("camp", "teleport.cooldown-minutes", 30));
        rm.checkCooldown(p, ResidentModule.COOLDOWN_CAMP_TP, cooldown);
        rm.teleports().start(p, civ.balance().getInt("camp", "teleport.warmup-seconds", 10), player -> {
            Camp c = civ.state().camp(camp.id());
            if (c == null || !c.members().contains(player.getUniqueId())) return null;
            try {
                rm.checkCooldown(player, ResidentModule.COOLDOWN_CAMP_TP, cooldown);
            } catch (CivException e) {
                civ.messages().send(player, e.key(), e.args());
                return null;
            }
            rm.markCooldown(player, ResidentModule.COOLDOWN_CAMP_TP);
            civ.messages().send(player, "camp.teleport.done", Messages.arg("camp", c.name()));
            return module.teleportTarget(c);
        }, null);
    }

    private void refresh(Player p) throws CivException {
        Camp camp = mine(p);
        CampLayout l = module.layout(camp);
        if (l == null) throw new CivException("camp.place.no-template");
        module.refresh(l);
        civ.messages().send(p, "camp.refresh.done");
    }

    /** /camp undo: only in the first minutes after placement (audit C-27). */
    private void undo(Player p) throws CivException {
        Camp camp = owned(p);
        Duration window = Duration.ofMinutes(civ.balance().getInt("camp", "undo-minutes", 5));
        if (camp.founded().plus(window).isBefore(Instant.now())) throw new CivException("camp.undo.expired", Messages.arg("minutes", window.toMinutes()));
        module.destroy(camp, null);
        module.giveDoor(p);
        civ.messages().send(p, "camp.undo.done");
    }

    private void disband(Player p) throws CivException {
        Camp camp = owned(p);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("camp.disband.confirm-title"), m.lines("camp.disband.confirm-body", Messages.arg("camp", camp.name())),
                m.component("prompt.yes"), m.component("prompt.no"), player -> {
                    Camp c = civ.state().camp(camp.id());
                    if (c == null || !c.owner().equals(player.getUniqueId())) return;
                    module.destroy(c, "camp.destroyed.disbanded");
                });
    }

    private void location(Player p) throws CivException {
        Camp camp = mine(p);
        CampLayout l = module.layout(camp);
        BlockPos c = l == null ? camp.origin() : l.center();
        civ.messages().send(p, "camp.location", Messages.arg("camp", camp.name()), Messages.arg("x", c.x()), Messages.arg("y", c.y()),
                Messages.arg("z", c.z()));
    }

    private void list(CommandSender sender) {
        List<String> entries = new ArrayList<>();
        for (Camp c : civ.state().camps()) {
            entries.add(civ.messages().plain("camp.list-entry", Messages.arg("camp", c.name()), Messages.arg("owner", name(c.owner())),
                    Messages.arg("members", c.members().size())));
        }
        civ.messages().send(sender, "camp.list", Messages.arg("count", entries.size()),
                Messages.arg("list", entries.isEmpty() ? "-" : String.join(", ", entries)));
    }

    private void showMotd(Player p) throws CivException {
        Camp camp = mine(p);
        if (camp.motd() == null) throw new CivException("camp.motd.none");
        civ.messages().send(p, "camp.info.motd", Messages.arg("text", camp.motd()));
    }

    private void setMotd(Player p, String text) throws CivException {
        Camp camp = owned(p);
        String clean = text.length() > 200 ? text.substring(0, 200) : text;
        camp.motd(clean.equals("-") ? null : clean);
        civ.state().save(camp);
        Channels.camp(camp, "camp.motd.set", Messages.arg("text", clean));
    }

    static String percent(double v) {
        return Format.percent(v);
    }
}
