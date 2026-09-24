package com.civcraft.resident;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.civ.CivPerms;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.ui.Prompts;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.event.CivRenamedEvent;
import com.civcraft.event.TownRenamedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.victory.VictoryApi;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import org.bukkit.entity.Player;

/**
 * The paid {@code /rename} service (spec §6.8): rename one's town, civilization or civilization tag. Each
 * purchase is one use: the donation store (or an admin) grants uses with {@code /rename give}; players with
 * {@code civcraft.rename.unlimited} need none. Not available during war or ≤ 7 days before the civilization's
 * victory; renaming a civilization or its tag is governed by the {@code rename} /civ perm (leaders by default).
 */
final class Rename {

    static final String UNLIMITED = "civcraft.rename.unlimited";
    static final String ADMIN = "civcraft.admin";

    private final CivCraft civ;
    private final ResidentModule residents;

    Rename(CivCraft civ, ResidentModule residents) {
        this.civ = civ;
        this.residents = residents;
    }

    LiteralArgumentBuilder<CommandSourceStack> command() {
        return lit("rename")
                .executes(Cmd.player((p, ctx) -> help(p)))
                .then(lit("town").then(word("name").executes(Cmd.player((p, ctx) -> town(p, arg(ctx, "name"))))))
                .then(lit("civ").then(word("name").executes(Cmd.player((p, ctx) -> civName(p, arg(ctx, "name"))))))
                .then(lit("tag").then(word("tag").executes(Cmd.player((p, ctx) -> tag(p, arg(ctx, "tag"))))))
                .then(lit("give").requires(Cmd.perm(ADMIN))
                        .then(word("player").suggests(CmdKit.players())
                                .executes(Cmd.run(ctx -> give(ctx.getSource().getSender(), arg(ctx, "player"), 1)))
                                .then(Cmd.arg("count", IntegerArgumentType.integer(-100, 100))
                                        .executes(Cmd.run(ctx -> give(ctx.getSource().getSender(), arg(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    private void help(Player p) {
        civ.messages().send(p, "rename.help", Messages.arg("uses", usesText(p)));
    }

    private String usesText(Player p) {
        if (p.hasPermission(UNLIMITED)) return "∞";
        return String.valueOf(residents.data(p.getUniqueId()).renames());
    }

    private void give(org.bukkit.command.CommandSender sender, String playerName, int count) throws CivException {
        Resident r = Lookup.resident(playerName);
        ResidentData d = residents.data(r.uuid());
        d.renames(Math.max(0, d.renames() + count));
        residents.save(d);
        civ.messages().send(sender, "rename.given", Messages.arg("name", r.name()), Messages.arg("uses", d.renames()));
        Player target = org.bukkit.Bukkit.getPlayer(r.uuid());
        if (target != null && count > 0) civ.messages().send(target, "rename.received", Messages.arg("uses", d.renames()));
    }

    // --- checks ---------------------------------------------------------------------------------

    private void checkUses(Player p) throws CivException {
        if (!p.hasPermission(UNLIMITED) && residents.data(p.getUniqueId()).renames() <= 0) {
            throw new CivException("rename.no-uses");
        }
    }

    private void consumeUse(Player p) {
        if (p.hasPermission(UNLIMITED)) return;
        ResidentData d = residents.data(p.getUniqueId());
        d.renames(Math.max(0, d.renames() - 1));
        residents.save(d);
    }

    /** War and victory locks (§6.8). */
    private void checkLocks(Civilization c) throws CivException {
        if (c == null) return;
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (dip != null && (dip.isAtWar(c.id())
                || (civ.balance().file("resident").getBoolean("rename.block-during-war-time", true) && dip.isWarTime()))) {
            throw new CivException("rename.war");
        }
        VictoryApi victory = civ.apiOrNull(VictoryApi.class);
        if (victory != null) {
            Duration left = victory.timeToVictory(c);
            int days = civ.balance().getInt("resident", "rename.victory-lock-days", 7);
            if (left != null && left.compareTo(Duration.ofDays(days)) <= 0) {
                throw new CivException("rename.victory", Messages.arg("days", days));
            }
        }
    }

    private Town mayorTown(Player p) throws CivException {
        Town t = civ.state().townOf(p);
        if (t == null) throw new CivException("error.not-in-town");
        if (!t.isMayor(p.getUniqueId())) throw new CivException("rename.not-mayor");
        return t;
    }

    private Civilization permittedCiv(Player p) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null) throw new CivException("error.not-in-civ");
        CivPerms.check(p, c, "rename");
        return c;
    }

    // --- actions --------------------------------------------------------------------------------

    private void town(Player p, String input) throws CivException {
        Town t = mayorTown(p);
        String name = Names.name(input);
        validateTownName(t, name);
        checkLocks(civ.state().civOf(t));
        checkUses(p);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("rename.confirm-title"),
                m.lines("rename.confirm-town", Messages.arg("old", t.name()), Messages.arg("name", name)),
                m.component("prompt.yes"), m.component("prompt.no"), player -> run(player, () -> {
                    Town now = mayorTown(player);
                    if (!now.id().equals(t.id())) return;
                    validateTownName(now, name);
                    checkLocks(civ.state().civOf(now));
                    checkUses(player);
                    String old = now.name();
                    civ.state().renameTown(now, name);
                    consumeUse(player);
                    new TownRenamedEvent(now.id(), old, name).call();
                    Channels.global("rename.town-done", Messages.arg("old", old), Messages.arg("name", name));
                }));
    }

    private void validateTownName(Town t, String name) throws CivException {
        Town other = civ.state().townByName(name);
        if (other != null && other != t) throw new CivException("town.name-taken", Messages.arg("name", name));
        if (name.equals(t.name())) throw new CivException("rename.same");
    }

    private void civName(Player p, String input) throws CivException {
        Civilization c = permittedCiv(p);
        String name = Names.name(input);
        validateCivName(c, name);
        checkLocks(c);
        checkUses(p);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("rename.confirm-title"),
                m.lines("rename.confirm-civ", Messages.arg("old", c.name()), Messages.arg("name", name)),
                m.component("prompt.yes"), m.component("prompt.no"), player -> run(player, () -> {
                    Civilization now = permittedCiv(player);
                    if (!now.id().equals(c.id())) return;
                    validateCivName(now, name);
                    checkLocks(now);
                    checkUses(player);
                    String old = now.name();
                    civ.state().renameCiv(now, name, now.tag());
                    consumeUse(player);
                    new CivRenamedEvent(now.id(), old, name, now.tag(), now.tag()).call();
                    Channels.global("rename.civ-done", Messages.arg("old", old), Messages.arg("name", name));
                }));
    }

    private void validateCivName(Civilization c, String name) throws CivException {
        Civilization other = civ.state().civByName(name);
        if (other != null && other != c) throw new CivException("rename.civ-taken", Messages.arg("name", name));
        if (name.equals(c.name())) throw new CivException("rename.same");
    }

    private void tag(Player p, String input) throws CivException {
        Civilization c = permittedCiv(p);
        String tag = Names.tag(input);
        validateTag(c, tag);
        checkLocks(c);
        checkUses(p);
        Messages m = civ.messages();
        Prompts.confirm(p, m.component("rename.confirm-title"),
                m.lines("rename.confirm-tag", Messages.arg("old", c.tag() == null ? "—" : c.tag()), Messages.arg("name", tag)),
                m.component("prompt.yes"), m.component("prompt.no"), player -> run(player, () -> {
                    Civilization now = permittedCiv(player);
                    if (!now.id().equals(c.id())) return;
                    validateTag(now, tag);
                    checkLocks(now);
                    checkUses(player);
                    String old = now.tag();
                    civ.state().renameCiv(now, now.name(), tag);
                    consumeUse(player);
                    new CivRenamedEvent(now.id(), now.name(), now.name(), old, tag).call();
                    Channels.global("rename.tag-done", Messages.arg("civ", now.name()),
                            Messages.arg("old", old == null ? "—" : old), Messages.arg("name", tag));
                }));
    }

    private void validateTag(Civilization c, String tag) throws CivException {
        Civilization other = civ.state().civByTag(tag);
        if (other == null) other = civ.state().civByName(tag);
        if (other != null && other != c) throw new CivException("rename.tag-taken", Messages.arg("tag", tag));
        if (tag.equals(c.tag())) throw new CivException("rename.same");
    }

    private interface Step {
        void run() throws CivException;
    }

    private void run(Player p, Step step) {
        try {
            step.run();
        } catch (CivException e) {
            civ.messages().send(p, e.key(), e.args());
        }
    }
}
