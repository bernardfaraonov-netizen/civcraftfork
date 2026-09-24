package com.civcraft.town;

import static com.civcraft.resident.CmdKit.arg;
import static com.civcraft.resident.CmdKit.lit;
import static com.civcraft.resident.CmdKit.word;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.resident.CmdKit;
import com.civcraft.resident.Lookup;
import com.civcraft.resident.Names;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Town groups (spec §7.1–§7.2) and outlaws (§19.2 /t outlaw). */
final class TownGroupCommands {

    private final CivCraft civ;
    private final TownModule module;

    TownGroupCommands(CivCraft civ, TownModule module) {
        this.civ = civ;
        this.module = module;
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> groupNames() {
        return (ctx, builder) -> {
            if (ctx.getSource().getExecutor() instanceof Player p) {
                Town t = civ.state().townOf(p);
                try {
                    t = module.selectedTown(p);
                } catch (CivException ignored) {
                    // fall back to the own town
                }
                if (t != null) {
                    String rem = builder.getRemainingLowerCase();
                    for (String g : t.groups().keySet()) if (g.startsWith(rem)) builder.suggest(g);
                }
            }
            return builder.buildFuture();
        };
    }

    LiteralArgumentBuilder<CommandSourceStack> groupCommand() {
        return lit("group").executes(Cmd.player((p, ctx) -> info(p, null)))
                .then(lit("info").executes(Cmd.player((p, ctx) -> info(p, null)))
                        .then(word("group").suggests(groupNames()).executes(Cmd.player((p, ctx) -> info(p, arg(ctx, "group"))))))
                .then(lit("new").then(word("group").executes(Cmd.player((p, ctx) -> create(p, arg(ctx, "group"))))))
                .then(lit("delete").then(word("group").suggests(groupNames()).executes(Cmd.player((p, ctx) -> delete(p, arg(ctx, "group"))))))
                .then(lit("add")
                        .then(lit("town").then(word("town").suggests(CmdKit.towns()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massAdd(p, "town", arg(ctx, "town"), arg(ctx, "group")))))))
                        .then(lit("civ").then(word("civ").suggests(CmdKit.civs()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massAdd(p, "civ", arg(ctx, "civ"), arg(ctx, "group")))))))
                        .then(word("player").suggests(CmdKit.players()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> add(p, arg(ctx, "player"), arg(ctx, "group")))))))
                .then(lit("remove")
                        .then(lit("town").then(word("town").suggests(CmdKit.towns()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massRemove(p, "town", arg(ctx, "town"), arg(ctx, "group")))))))
                        .then(lit("civ").then(word("civ").suggests(CmdKit.civs()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massRemove(p, "civ", arg(ctx, "civ"), arg(ctx, "group")))))))
                        .then(lit("bum").then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massRemove(p, "bum", null, arg(ctx, "group"))))))
                        .then(lit("all").then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> massRemove(p, "all", null, arg(ctx, "group"))))))
                        .then(word("player").suggests(CmdKit.players()).then(word("group").suggests(groupNames())
                                .executes(Cmd.player((p, ctx) -> remove(p, arg(ctx, "player"), arg(ctx, "group")))))))
                .then(lit("migrate").then(word("from").suggests(groupNames()).then(word("to").suggests(groupNames())
                        .executes(Cmd.player((p, ctx) -> migrate(p, arg(ctx, "from"), arg(ctx, "to"), true))))))
                .then(lit("move").then(word("from").suggests(groupNames()).then(word("to").suggests(groupNames())
                        .executes(Cmd.player((p, ctx) -> migrate(p, arg(ctx, "from"), arg(ctx, "to"), false))))));
    }

    LiteralArgumentBuilder<CommandSourceStack> leaveGroupCommand() {
        return lit("leavegroup").then(word("town").suggests(CmdKit.towns()).then(word("group")
                .executes(Cmd.player((p, ctx) -> leaveGroup(p, arg(ctx, "town"), arg(ctx, "group"))))));
    }

    // --- groups ---------------------------------------------------------------------------------

    private Town managed(Player p) throws CivException {
        Town town = module.selectedTown(p);
        module.requireManage(p, town);
        return town;
    }

    private String existing(Town town, String name) throws CivException {
        String g = name.toLowerCase(Locale.ROOT);
        if (!town.groups().containsKey(g)) throw new CivException("town.group.unknown", Messages.arg("group", name));
        return g;
    }

    /** Groups created by the civ owner are managed only by the owner (spec §7.1 assumption). */
    private void checkOwnerGroup(Player p, Town town, String group) throws CivException {
        if (!town.ownerGroups().contains(group)) return;
        Civilization c = civ.state().civOf(town);
        if (c == null || !p.getUniqueId().equals(c.owner())) throw new CivException("town.group.owner-group");
    }

    private void info(Player p, String group) throws CivException {
        Town town = module.selectedTown(p);
        Messages m = civ.messages();
        if (group == null) {
            m.send(p, "town.group.list-header", Messages.arg("town", town.name()));
            for (Map.Entry<String, Set<UUID>> e : town.groups().entrySet()) {
                m.sendRaw(p, "town.group.list-entry", Messages.arg("group", e.getKey()), Messages.arg("count", e.getValue().size()));
            }
            return;
        }
        String g = existing(town, group);
        List<String> names = new ArrayList<>();
        for (UUID id : town.group(g)) {
            Resident r = civ.state().resident(id);
            if (r != null) names.add(r.name());
        }
        m.send(p, "town.group.members", Messages.arg("group", g), Messages.arg("list", names.isEmpty() ? "-" : String.join(", ", names)));
    }

    private void create(Player p, String input) throws CivException {
        Town town = managed(p);
        String g = Names.group(input);
        if (town.groups().containsKey(g)) throw new CivException("town.group.exists", Messages.arg("group", g));
        int max = civ.balance().getInt("town", "max-groups", 20);
        if (town.groups().size() >= max) throw new CivException("town.group.limit", Messages.arg("max", max));
        town.groups().put(g, new HashSet<>());
        Civilization c = civ.state().civOf(town);
        if (c != null && p.getUniqueId().equals(c.owner())) town.ownerGroups().add(g);
        civ.state().save(town);
        Channels.town(town, "town.group.created", Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    private void delete(Player p, String input) throws CivException {
        Town town = managed(p);
        String g = existing(town, input);
        if (Town.PROTECTED_GROUPS.contains(g)) throw new CivException("town.group.protected");
        checkOwnerGroup(p, town, g);
        if (!town.group(g).isEmpty()) throw new CivException("town.group.not-empty");
        town.groups().remove(g);
        town.ownerGroups().remove(g);
        for (Claim claim : civ.state().claims(town)) {
            if (claim.groups().remove(g)) civ.state().save(claim);
        }
        civ.state().save(town);
        Channels.town(town, "town.group.deleted", Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    private void checkProtectedChange(Player p, Town town, String g, Resident target, boolean adding) throws CivException {
        if (g.equals(Town.RESIDENTS)) throw new CivException(adding ? "town.group.residents-add" : "town.group.residents-remove");
        if (g.equals(Town.MAYORS)) {
            Civilization c = civ.state().civOf(town);
            boolean leader = c != null && c.isLeader(p.getUniqueId());
            if (!town.isMayor(p.getUniqueId()) && !leader) throw new CivException("town.group.mayors-rights");
            // The conqueror cannot appoint mayors before the town capitulated (spec §18.1).
            if (town.isCaptured() && !town.isMayor(p.getUniqueId())) throw new CivException("town.group.captured-mayor");
            if (!adding && target.uuid().equals(p.getUniqueId())) throw new CivException("town.group.remove-self-mayor");
            if (!adding && town.mayors().size() <= 1) throw new CivException("town.group.last-mayor");
        }
        if (adding && (g.equals(Town.MAYORS) || g.equals(Town.ASSISTANTS)) && !town.isResident(target.uuid())) {
            throw new CivException("town.group.only-residents", Messages.arg("name", target.name()));
        }
    }

    private void add(Player p, String name, String group) throws CivException {
        Town town = managed(p);
        String g = existing(town, group);
        checkOwnerGroup(p, town, g);
        Resident target = Lookup.resident(name);
        checkProtectedChange(p, town, g, target, true);
        if (!town.groups().get(g).add(target.uuid())) throw new CivException("town.group.already", Messages.arg("name", target.name()), Messages.arg("group", g));
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.group.added", Messages.arg("name", target.name()), Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    private void remove(Player p, String name, String group) throws CivException {
        Town town = managed(p);
        String g = existing(town, group);
        checkOwnerGroup(p, town, g);
        Resident target = Lookup.resident(name);
        checkProtectedChange(p, town, g, target, false);
        if (!town.groups().get(g).remove(target.uuid())) throw new CivException("town.group.not-member", Messages.arg("name", target.name()), Messages.arg("group", g));
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.group.removed", Messages.arg("name", target.name()), Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    private Set<UUID> members(String kind, String name) throws CivException {
        Set<UUID> result = new HashSet<>();
        if (kind.equals("town")) {
            result.addAll(Lookup.town(name).residents());
        } else {
            Civilization c = Lookup.civ(name);
            for (Town t : civ.state().towns(c)) result.addAll(t.residents());
        }
        return result;
    }

    private void massAdd(Player p, String kind, String name, String group) throws CivException {
        Town town = managed(p);
        String g = existing(town, group);
        if (Town.PROTECTED_GROUPS.contains(g)) throw new CivException("town.group.protected");
        checkOwnerGroup(p, town, g);
        int added = 0;
        for (UUID id : members(kind, name)) if (town.groups().get(g).add(id)) added++;
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.group.mass-added", Messages.arg("count", added), Messages.arg("source", name),
                Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    private void massRemove(Player p, String kind, String name, String group) throws CivException {
        Town town = managed(p);
        String g = existing(town, group);
        if (Town.PROTECTED_GROUPS.contains(g)) throw new CivException("town.group.protected");
        checkOwnerGroup(p, town, g);
        Set<UUID> set = town.groups().get(g);
        int before = set.size();
        switch (kind) {
            case "all" -> set.clear();
            case "bum" -> set.removeIf(id -> civ.state().civOf(civ.state().resident(id)) == null);
            default -> set.removeAll(members(kind, name));
        }
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.group.mass-removed", Messages.arg("count", before - set.size()),
                Messages.arg("source", name == null ? kind : name), Messages.arg("group", g), Messages.arg("by", p.getName()));
    }

    /** migrate: move all members (removing them from the source); move: copy. Leaders only (spec §7.2). */
    private void migrate(Player p, String fromName, String toName, boolean removeFromSource) throws CivException {
        Town town = module.selectedTown(p);
        Civilization c = civ.state().civOf(town);
        if (c == null || !c.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
        String from = existing(town, fromName);
        String to = existing(town, toName);
        if (from.equals(to)) throw new CivException("town.group.same");
        if (Town.PROTECTED_GROUPS.contains(to) || (removeFromSource && Town.PROTECTED_GROUPS.contains(from))) {
            throw new CivException("town.group.protected");
        }
        checkOwnerGroup(p, town, from);
        checkOwnerGroup(p, town, to);
        Set<UUID> source = town.groups().get(from);
        town.groups().get(to).addAll(source);
        int n = source.size();
        if (removeFromSource) source.clear();
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, removeFromSource ? "town.group.migrated" : "town.group.copied", Messages.arg("count", n),
                Messages.arg("from", from), Messages.arg("to", to), Messages.arg("by", p.getName()));
    }

    private void leaveGroup(Player p, String townName, String group) throws CivException {
        Town town = Lookup.town(townName);
        String g = existing(town, group);
        if (g.equals(Town.RESIDENTS)) throw new CivException("town.group.residents-remove");
        if (!town.group(g).contains(p.getUniqueId())) throw new CivException("town.group.not-member", Messages.arg("name", p.getName()), Messages.arg("group", g));
        if (g.equals(Town.MAYORS) && town.mayors().size() <= 1) throw new CivException("town.group.last-mayor");
        town.groups().get(g).remove(p.getUniqueId());
        civ.state().save(town);
        civ.stats().invalidate();
        module.production().invalidate();
        Channels.town(town, "town.group.left", Messages.arg("name", p.getName()), Messages.arg("group", g));
    }

    // --- outlaws --------------------------------------------------------------------------------

    LiteralArgumentBuilder<CommandSourceStack> outlawCommand() {
        return lit("outlaw").executes(CmdKit.help("town.outlaw.help"))
                .then(lit("add").then(word("player").suggests(CmdKit.players())
                        .executes(Cmd.player((p, ctx) -> outlawPlayer(p, arg(ctx, "player"), true, false)))
                        .then(lit("civ").executes(Cmd.player((p, ctx) -> outlawPlayer(p, arg(ctx, "player"), true, true))))))
                .then(lit("remove").then(word("player").suggests(CmdKit.players())
                        .executes(Cmd.player((p, ctx) -> outlawPlayer(p, arg(ctx, "player"), false, false)))
                        .then(lit("civ").executes(Cmd.player((p, ctx) -> outlawPlayer(p, arg(ctx, "player"), false, true))))))
                .then(lit("list").executes(Cmd.player((p, ctx) -> outlawList(p, null)))
                        .then(word("town").suggests(CmdKit.towns()).executes(Cmd.player((p, ctx) -> outlawList(p, arg(ctx, "town"))))))
                .then(lit("check").then(word("player").suggests(CmdKit.players()).executes(Cmd.player((p, ctx) -> outlawCheck(p, arg(ctx, "player"))))))
                .then(massOutlaw("civ", true))
                .then(massOutlaw("town", false))
                .then(legacy("addallciv", true, true)).then(legacy("removeallciv", true, false))
                .then(legacy("addall", false, true)).then(legacy("removeall", false, false));
    }

    private LiteralArgumentBuilder<CommandSourceStack> massOutlaw(String name, boolean isCiv) {
        return lit(name)
                .then(lit("remove").then(word("target").suggests(isCiv ? CmdKit.civs() : CmdKit.towns())
                        .executes(Cmd.player((p, ctx) -> outlawMass(p, isCiv, arg(ctx, "target"), false, false)))
                        .then(lit("civ").executes(Cmd.player((p, ctx) -> outlawMass(p, isCiv, arg(ctx, "target"), false, true))))))
                .then(word("target").suggests(isCiv ? CmdKit.civs() : CmdKit.towns())
                        .executes(Cmd.player((p, ctx) -> outlawMass(p, isCiv, arg(ctx, "target"), true, false)))
                        .then(lit("civ").executes(Cmd.player((p, ctx) -> outlawMass(p, isCiv, arg(ctx, "target"), true, true)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> legacy(String name, boolean isCiv, boolean add) {
        return lit(name).then(word("target").suggests(isCiv ? CmdKit.civs() : CmdKit.towns())
                .executes(Cmd.player((p, ctx) -> outlawMass(p, isCiv, arg(ctx, "target"), add, false))));
    }

    /** Towns affected by an outlaw command: the selected one, or all towns of the civ with the civ flag (leaders). */
    private List<Town> outlawTowns(Player p, boolean civWide) throws CivException {
        Town town = module.selectedTown(p);
        if (!civWide) {
            if (!town.isOfficial(p.getUniqueId())) {
                Civilization c = civ.state().civOf(town);
                if (c == null || !c.isLeader(p.getUniqueId())) throw new CivException("town.no-rights", Messages.arg("town", town.name()));
            }
            return List.of(town);
        }
        Civilization c = civ.state().civOf(town);
        if (c == null || !c.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
        return civ.state().towns(c);
    }

    private void checkOutlawTarget(Town town, Resident target) throws CivException {
        if (town.isResident(target.uuid())) throw new CivException("town.outlaw.own-resident", Messages.arg("name", target.name()));
        Town theirs = civ.state().townOf(target);
        // Members of the own civilization can be outlawed only in native towns (spec §19.2, CL 1.4).
        if (theirs != null && Objects.equals(theirs.civId(), town.civId()) && town.status() != TownStatus.NATIVE) {
            throw new CivException("town.outlaw.own-civ-native", Messages.arg("name", target.name()));
        }
    }

    private void outlawPlayer(Player p, String name, boolean add, boolean civWide) throws CivException {
        Resident target = Lookup.resident(name);
        int changed = 0;
        for (Town town : outlawTowns(p, civWide)) {
            if (add) {
                try {
                    checkOutlawTarget(town, target);
                } catch (CivException e) {
                    if (!civWide) throw e;
                    continue;
                }
                if (town.outlaws().add(target.uuid())) changed++;
            } else if (town.outlaws().remove(target.uuid())) {
                changed++;
            }
            civ.state().save(town);
            Channels.town(town, add ? "town.outlaw.added" : "town.outlaw.removed", Messages.arg("name", target.name()), Messages.arg("by", p.getName()));
        }
        if (changed == 0) throw new CivException(add ? "town.outlaw.already" : "town.outlaw.not-outlaw", Messages.arg("name", target.name()));
        Channels.resident(target, add ? "town.outlaw.you-added" : "town.outlaw.you-removed", Messages.arg("count", changed));
    }

    private void outlawMass(Player p, boolean isCiv, String name, boolean add, boolean civWide) throws CivException {
        List<Town> towns = outlawTowns(p, civWide);
        Town mine = module.selectedTown(p);
        Set<UUID> targets = new HashSet<>();
        if (isCiv) {
            Civilization target = Lookup.civ(name);
            if (Objects.equals(target.id(), mine.civId())) throw new CivException("town.outlaw.own-civ");
            for (Town t : civ.state().towns(target)) targets.addAll(t.residents());
        } else {
            Town target = Lookup.town(name);
            if (target.id().equals(mine.id()) || Objects.equals(target.civId(), mine.civId())) throw new CivException("town.outlaw.own-town");
            targets.addAll(target.residents());
        }
        int changed = 0;
        for (Town town : towns) {
            for (UUID id : targets) {
                if (town.isResident(id)) continue;
                if (add ? town.outlaws().add(id) : town.outlaws().remove(id)) changed++;
            }
            civ.state().save(town);
        }
        civ.messages().send(p, add ? "town.outlaw.mass-added" : "town.outlaw.mass-removed", Messages.arg("count", changed), Messages.arg("target", name));
    }

    private void outlawList(Player p, String townName) throws CivException {
        Town town = townName == null ? module.selectedTown(p) : Lookup.town(townName);
        List<String> names = new ArrayList<>();
        for (UUID id : town.outlaws()) {
            Resident r = civ.state().resident(id);
            if (r != null) names.add(r.name());
        }
        civ.messages().send(p, "town.outlaw.list", Messages.arg("town", town.name()),
                Messages.arg("list", names.isEmpty() ? "-" : String.join(", ", names)));
    }

    private void outlawCheck(Player p, String name) throws CivException {
        Civilization c = civ.state().civOf(p);
        if (c == null || !c.isLeader(p.getUniqueId())) throw new CivException("civ.leader-only");
        Resident target = Lookup.resident(name);
        List<String> towns = new ArrayList<>();
        for (Town t : civ.state().towns(c)) if (t.outlaws().contains(target.uuid())) towns.add(t.name());
        civ.messages().send(p, "town.outlaw.check", Messages.arg("name", target.name()),
                Messages.arg("list", towns.isEmpty() ? "-" : String.join(", ", towns)));
    }
}
