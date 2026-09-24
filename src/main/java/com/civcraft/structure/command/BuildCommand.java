package com.civcraft.structure.command;

import static com.civcraft.core.text.Messages.arg;
import static com.civcraft.core.text.Messages.money;

import com.civcraft.CivCraft;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.ui.Prompts;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Durations;
import com.civcraft.effect.StatSheet;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.structure.Structure;
import com.civcraft.structure.StructureModule;
import com.civcraft.structure.placement.Orientation;
import com.civcraft.structure.type.NameMatcher;
import com.civcraft.structure.type.Requirement;
import com.civcraft.structure.type.StructureType;
import com.civcraft.template.Template;
import com.civcraft.template.TemplateService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * {@code /build} ({@code /b}) — spec 01 §19.4, 02 §1.3–1.7: GUI, placement with height and theme in any order,
 * progress, cancel, demolish, repair, refresh, validate, nearest, bonuses, info and the town default theme.
 */
public final class BuildCommand {

    private final StructureModule module;
    private final CivCraft civ;

    public BuildCommand(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Cmd.literal("build")
                .executes(Cmd.player((p, ctx) -> openMenu(p)))
                .then(Cmd.literal("help").executes(Cmd.run(ctx -> civ.messages().sendRaw(ctx.getSource().getSender(), "structure.help"))))
                .then(Cmd.literal("list").executes(Cmd.player((p, ctx) -> openMenu(p))))
                .then(Cmd.literal("yes").executes(Cmd.player((p, ctx) -> module.placement().confirm(p))))
                .then(Cmd.literal("no").executes(Cmd.player((p, ctx) -> {
                    module.placement().cancel(p);
                    civ.messages().send(p, "structure.session-cancelled");
                })))
                .then(progress("progress"))
                .then(progress("calc"))
                .then(Cmd.literal("cancel")
                        .executes(Cmd.player((p, ctx) -> cancel(p, false)))
                        .then(Cmd.literal("wonder").executes(Cmd.player((p, ctx) -> cancel(p, true)))))
                .then(Cmd.literal("nearest").executes(Cmd.player((p, ctx) -> nearest(p))))
                .then(Cmd.literal("demolish").executes(Cmd.player((p, ctx) -> demolishMenu(p))))
                .then(Cmd.literal("demolishnearest").executes(Cmd.player((p, ctx) -> demolishNearest(p))))
                .then(Cmd.literal("repair").executes(Cmd.player((p, ctx) -> repairNearest(p))))
                .then(Cmd.literal("repairnearest").executes(Cmd.player((p, ctx) -> repairNearest(p))))
                .then(Cmd.literal("refresh").executes(Cmd.player((p, ctx) -> refreshNearest(p))))
                .then(Cmd.literal("refreshnearest").executes(Cmd.player((p, ctx) -> refreshNearest(p))))
                .then(Cmd.literal("validatenearest").executes(Cmd.player((p, ctx) -> validateNearest(p))))
                .then(Cmd.literal("bonuses").executes(Cmd.player((p, ctx) -> bonuses(p))))
                .then(Cmd.literal("info")
                        .then(Cmd.arg("type", StringArgumentType.greedyString()).suggests(Cmd.suggest(this::typeNames))
                                .executes(Cmd.run(ctx -> info(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "type"))))))
                .then(Cmd.literal("theme")
                        .executes(Cmd.player((p, ctx) -> themes(p)))
                        .then(Cmd.arg("theme", StringArgumentType.word()).suggests(Cmd.suggest(() -> module.themes().keySet()))
                                .executes(Cmd.player((p, ctx) -> setTheme(p, StringArgumentType.getString(ctx, "theme"))))))
                .then(Cmd.arg("args", StringArgumentType.greedyString()).suggests(Cmd.suggest(this::typeNames))
                        .executes(Cmd.player((p, ctx) -> build(p, StringArgumentType.getString(ctx, "args")))))
                .build();
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> progress(String name) {
        return Cmd.literal(name)
                .executes(Cmd.player((p, ctx) -> progress(p, module.selectedTown(p))))
                .then(Cmd.arg("town", StringArgumentType.word())
                        .suggests(Cmd.suggest(() -> civ.state().towns().stream().map(Town::name).toList()))
                        .executes(Cmd.player((p, ctx) -> {
                            String name1 = StringArgumentType.getString(ctx, "town");
                            Town t = civ.state().townByName(name1);
                            if (t == null) throw new CivException("error.unknown-town", arg("name", name1));
                            progress(p, t);
                        })));
    }

    private Collection<String> typeNames() {
        List<String> names = new ArrayList<>();
        for (StructureType t : module.types().sorted()) {
            if (!t.warOnly()) names.add(t.name());
        }
        return names;
    }

    private void openMenu(Player p) throws CivException {
        Town town = module.selectedTown(p);
        new BuildMenu(module, civ, town, module.defaultTheme(town)).open(p);
    }

    // --- /build <name> [height] [theme] ------------------------------------------------------------------------------

    private void build(Player p, String input) throws CivException {
        Integer absoluteY = null;
        String theme = null;
        List<String> nameParts = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (String token : input.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (absoluteY == null && token.matches("[+-]?\\d{1,4}")) {
                absoluteY = Orientation.parseHeight(token, p.getLocation().getBlockY());
                if (absoluteY == null) throw new CivException("structure.error.bad-height", arg("value", token));
                continue;
            }
            String th = theme == null ? matchTheme(token) : null;
            if (th != null && !nameParts.isEmpty()) {
                theme = th;
                continue;
            }
            nameParts.add(token);
            extra.add(token);
        }
        if (nameParts.isEmpty()) {
            openMenu(p);
            return;
        }
        StructureType type = resolveType(String.join(" ", nameParts), civ.state().civOf(p));
        module.placement().begin(p, type, absoluteY, 0, theme, extra);
    }

    private String matchTheme(String token) {
        String t = NameMatcher.normalize(token);
        for (Map.Entry<String, String> e : module.themes().entrySet()) {
            if (e.getKey().equals(t) || NameMatcher.normalize(e.getValue()).equals(t)) return e.getKey();
        }
        return null;
    }

    /** Resolves a typed name; nation replacements are preferred for the player's civilization. */
    private StructureType resolveType(String name, Civilization c) throws CivException {
        List<StructureType> candidates = new ArrayList<>();
        for (StructureType t : module.types().all()) {
            if (t.warOnly()) continue;
            if (t.nation() != null && c != null && !module.types().nationMatches(t.nation(), c.nation())) continue;
            candidates.add(t);
        }
        List<StructureType> found = module.types().match(name, candidates);
        if (found.isEmpty()) throw new CivException("structure.error.unknown-type", arg("name", name));
        if (found.size() > 1) {
            List<String> names = new ArrayList<>();
            for (StructureType t : found) names.add(t.name());
            throw new CivException("structure.error.ambiguous", arg("names", String.join(", ", names)));
        }
        return found.getFirst();
    }

    // --- progress / cancel ------------------------------------------------------------------------------------------

    private void progress(Player p, Town town) {
        boolean any = false;
        civ.messages().sendRaw(p, "structure.progress-header", arg("town", town.name()));
        for (Structure s : module.structures(town)) {
            if (!s.isBuilding() && !s.repairing()) continue;
            any = true;
            long eta = module.secondsRemaining(s);
            String etaText = eta < 0 ? civ.messages().plain("structure.eta-never") : Durations.format(Duration.ofSeconds(eta));
            double done = s.repairing() ? s.repairDone() : s.hammersDone();
            double required = s.repairing() ? s.repairRequired() : s.hammersRequired();
            double fraction = required <= 0 ? 1 : done / required;
            civ.messages().sendRaw(p, s.repairing() ? "structure.progress-repair-line" : "structure.progress-line",
                    arg("name", s.typeDef().name()), arg("bar", civ.messages().parse(Format.progressBar(fraction, 20))),
                    arg("percent", (int) Math.floor(fraction * 100)), arg("done", Format.number(Math.floor(done))),
                    arg("required", Format.number(Math.ceil(required))), arg("eta", etaText), module.coords(s));
        }
        if (!any) civ.messages().sendRaw(p, "structure.progress-none");
        civ.messages().sendRaw(p, "structure.progress-rate", arg("rate", Format.number(module.buildRate(town))));
    }

    private void cancel(Player p, boolean wonder) throws CivException {
        Town town = module.selectedTown(p);
        CivException.check(module.canManage(p, town), "structure.error.not-manager");
        Structure s = wonder ? module.buildingWonder(town) : module.buildingNormal(town);
        if (s == null && !wonder) s = module.buildingWonder(town);
        if (s == null) throw new CivException("structure.error.nothing-building");
        Structure target = s;
        Prompts.confirm(p, civ.messages().component("structure.cancel.title", arg("name", target.typeDef().name())),
                List.of(civ.messages().component("structure.cancel.body", money("refund",
                        com.civcraft.core.util.Money.multiply(target.paidCost(), refundShare())))),
                civ.messages().component("structure.confirm.yes"), civ.messages().component("structure.confirm.no"),
                player -> {
                    if (target.removed() || !target.isBuilding()) return;
                    if (!module.canManage(player, town)) {
                        civ.messages().send(player, "structure.error.not-manager");
                        return;
                    }
                    module.cancelByPlayer(target);
                });
    }

    private double refundShare() {
        return civ.balance().getDouble("structures", "construction.cancel-refund", 0.5);
    }

    // --- nearest based commands -------------------------------------------------------------------------------------

    /** Nearest structure of the player's civilization (or town), within the same world. */
    private Structure nearestOwn(Player p) throws CivException {
        Resident r = civ.state().resident(p);
        Town own = civ.state().townOf(r);
        CivException.check(own != null, "error.not-in-town");
        Structure best = null;
        double bestD = Double.MAX_VALUE;
        String world = p.getWorld().getName();
        for (Structure s : module.all()) {
            if (s.removed() || !s.origin().world().equals(world)) continue;
            Town t = module.town(s);
            if (t == null) continue;
            boolean mine = t.id().equals(own.id()) || (own.civId() != null && own.civId().equals(t.civId()));
            if (!mine) continue;
            BlockPos c = s.center();
            double d = p.getLocation().distanceSquared(new org.bukkit.Location(p.getWorld(), c.x(), c.y(), c.z()));
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) throw new CivException("structure.error.none-nearby");
        return best;
    }

    private void nearest(Player p) throws CivException {
        Structure s = nearestOwn(p);
        Town t = module.town(s);
        String state = s.isBuilding() ? civ.messages().plain("structure.state.building", arg("percent", (int) (s.progress() * 100)))
                : s.isDestroyed() ? civ.messages().plain("structure.state.destroyed")
                : civ.messages().plain("structure.state.complete");
        civ.messages().sendRaw(p, "structure.nearest", arg("name", s.typeDef().name()), arg("town", t == null ? "?" : t.name()),
                module.coords(s), arg("state", state), money("upkeep", t == null ? 0 : module.upkeep(s, t)),
                arg("hp", s.hp()), arg("max", s.maxHp()), arg("id", s.id()), arg("level", s.level()));
        if (s.isActive()) module.behavior(s.type()).openGui(s, p);
    }

    private void demolishMenu(Player p) throws CivException {
        Town town = module.selectedTown(p);
        checkDemolish(p, town);
        new DemolishMenu(module, civ, town, this).open(p);
    }

    /** Mayors and assistants of the town, or holders of the {@code /civ perm} demolish right. */
    void checkDemolish(Player p, Town town) throws CivException {
        CivException.check(!town.isCaptured(), "structure.error.captured");
        if (town.isOfficial(p.getUniqueId())) return;
        module.checkCivPerm(p, civ.state().civOf(town), "demolish");
    }

    void confirmDemolish(Player p, Structure s) {
        Prompts.confirm(p, civ.messages().component("structure.demolish.title", arg("name", s.typeDef().name())),
                List.of(civ.messages().component("structure.demolish.body", module.coords(s))),
                civ.messages().component("structure.confirm.yes"), civ.messages().component("structure.confirm.no"),
                player -> {
                    try {
                        Town town = module.town(s);
                        if (town == null || s.removed()) return;
                        checkDemolish(player, town);
                        module.demolish(s);
                    } catch (CivException e) {
                        civ.messages().send(player, e.key(), e.args());
                    }
                });
    }

    private void demolishNearest(Player p) throws CivException {
        Structure s = nearestOwn(p);
        Town town = module.town(s);
        CivException.check(town != null, "error.not-in-town");
        checkDemolish(p, town);
        CivException.check(s.typeDef().demolishable(), "structure.error.no-demolish");
        confirmDemolish(p, s);
    }

    private void repairNearest(Player p) throws CivException {
        Structure s = null;
        double best = Double.MAX_VALUE;
        Town own = module.selectedTown(p);
        for (Structure o : module.structures(own)) {
            if (!o.isDestroyed() || !o.origin().world().equals(p.getWorld().getName())) continue;
            BlockPos c = o.center();
            double d = Math.pow(c.x() - p.getLocation().getX(), 2) + Math.pow(c.z() - p.getLocation().getZ(), 2);
            if (d < best) {
                best = d;
                s = o;
            }
        }
        if (s == null) throw new CivException("structure.error.nothing-to-repair");
        CivException.check(module.canManage(p, own), "structure.error.not-manager");
        Structure target = s;
        long cost = com.civcraft.core.util.Money.multiply(target.typeDef().cost(),
                civ.balance().getDouble("structures", "repair.cost-share", 0.5));
        Prompts.confirm(p, civ.messages().component("structure.repair.title", arg("name", target.typeDef().name())),
                List.of(civ.messages().component("structure.repair.body", money("cost", cost), module.coords(target))),
                civ.messages().component("structure.confirm.yes"), civ.messages().component("structure.confirm.no"),
                player -> {
                    try {
                        CivException.check(module.canManage(player, own), "structure.error.not-manager");
                        module.startRepair(target, player);
                    } catch (CivException e) {
                        civ.messages().send(player, e.key(), e.args());
                    }
                });
    }

    private void refreshNearest(Player p) throws CivException {
        Structure s = nearestOwn(p);
        Town town = module.town(s);
        CivException.check(town != null && module.canManage(p, town), "structure.error.not-manager");
        Prompts.confirm(p, civ.messages().component("structure.refresh.title", arg("name", s.typeDef().name())),
                List.of(civ.messages().component("structure.refresh.body")),
                civ.messages().component("structure.confirm.yes"), civ.messages().component("structure.confirm.no"),
                player -> {
                    try {
                        CivException.check(module.canManage(player, town), "structure.error.not-manager");
                        module.refresh(s, false);
                        civ.messages().send(player, "structure.refresh.started", arg("name", s.typeDef().name()));
                    } catch (CivException e) {
                        civ.messages().send(player, e.key(), e.args());
                    }
                });
    }

    /** Counts blocks that differ from the template (loaded chunks only) and the ground support. */
    private void validateNearest(Player p) throws CivException {
        Structure s = nearestOwn(p);
        Town town = module.town(s);
        CivException.check(town != null && module.canManage(p, town), "structure.error.not-manager");
        Template t = s.template();
        World w = s.origin().bukkitWorld();
        CivException.check(t != null && w != null, "structure.error.no-template");
        int missing = 0;
        int unloaded = 0;
        for (int y = 0; y < t.sizeY(); y++) {
            for (int z = 0; z < t.sizeZ(); z++) {
                for (int x = 0; x < t.sizeX(); x++) {
                    int bx = s.origin().x() + x;
                    int bz = s.origin().z() + z;
                    if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                        unloaded++;
                        continue;
                    }
                    if (t.block(x, y, z).getMaterial().isAir()) continue;
                    Block b = w.getBlockAt(bx, s.origin().y() + y, bz);
                    if (b.getType() != t.block(x, y, z).getMaterial()) missing++;
                }
            }
        }
        double support = TemplateService.groundSupport(w, s.origin(), s.sizeX(), s.sizeZ());
        civ.messages().sendRaw(p, "structure.validate", arg("name", s.typeDef().name()), arg("missing", missing),
                arg("support", Format.number(Math.floor(support * 100))), arg("unloaded", unloaded));
    }

    // --- bonuses / info / themes ------------------------------------------------------------------------------------

    private void bonuses(Player p) throws CivException {
        Town town = module.selectedTown(p);
        StatSheet sheet = civ.stats().town(town);
        civ.messages().sendRaw(p, "structure.bonuses.header", arg("town", town.name()));
        civ.messages().sendRaw(p, "structure.bonuses.cost", arg("value", Format.signedPercent(sheet.percent("build_cost"))));
        civ.messages().sendRaw(p, "structure.bonuses.hammers", arg("value", Format.signedPercent(sheet.percent("build_hammers"))));
        for (String tag : List.of("improvement", "wonder", "naval", "tower", "defense", "strategic")) {
            double c = sheet.percent("build_cost." + tag);
            double h = sheet.percent("build_hammers." + tag);
            if (c != 0 || h != 0) {
                civ.messages().sendRaw(p, "structure.bonuses.tag", arg("tag", tag), arg("cost", Format.signedPercent(c)),
                        arg("hammers", Format.signedPercent(h)));
            }
        }
        Civilization c = civ.state().civOf(town);
        if (c != null) {
            for (Map.Entry<String, Double> e : module.architectsBonuses(c.id()).entrySet()) {
                StructureType w = module.types().get(e.getKey());
                civ.messages().sendRaw(p, "structure.bonuses.architects", arg("name", w == null ? e.getKey() : w.name()),
                        arg("value", Format.signedPercent(-e.getValue())));
            }
        }
    }

    private void info(org.bukkit.command.CommandSender sender, String name) throws CivException {
        Civilization c = sender instanceof Player p ? civ.state().civOf(p) : null;
        StructureType t = resolveType(name, c);
        civ.messages().sendRaw(sender, "structure.info.header", arg("name", t.name()),
                arg("category", civ.messages().plain("structure.category." + t.category().key())), arg("era", t.era()));
        List<String> techs = new ArrayList<>();
        for (String tech : t.techs()) techs.add(module.techName(tech));
        List<String> reqs = new ArrayList<>();
        for (Requirement r : t.requires()) {
            StructureType rt = module.types().get(r.type());
            reqs.add((rt == null ? r.type() : rt.name()) + (r.scope() == Requirement.Scope.TOWN ? "" : " *"));
        }
        String none = civ.messages().plain("structure.info.none");
        civ.messages().sendRaw(sender, "structure.info.body",
                arg("tech", techs.isEmpty() ? none : String.join(", ", techs)),
                arg("requires", reqs.isEmpty() ? none : String.join(", ", reqs)),
                arg("size", t.chunksX() + "×" + t.chunksZ()), money("cost", t.cost()), money("upkeep", t.upkeep()),
                arg("hammers", Format.number(t.hammers())), arg("hp", t.hp()), arg("score", t.score()),
                arg("limit", t.limit() == 0 ? "∞" : String.valueOf(t.limit())),
                arg("civlimit", t.civLimit() == 0 ? "—" : String.valueOf(t.civLimit())),
                arg("slot", civ.messages().plain(t.slot() ? "structure.info.yes" : "structure.info.no")),
                arg("claim", civ.messages().plain("structure.claim." + t.claim().name().toLowerCase(Locale.ROOT))));
        if (sender instanceof Player p) {
            try {
                Town town = module.selectedTown(p);
                module.checkAvailable(town, t);
                civ.messages().sendRaw(p, "structure.info.available");
            } catch (CivException e) {
                civ.messages().sendRaw(p, "structure.info.unavailable", arg("reason", civ.messages().component(e.key(), e.args())));
            }
        }
    }

    private void themes(Player p) throws CivException {
        Town town = module.selectedTown(p);
        String current = module.defaultTheme(town);
        List<Component> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : module.themes().entrySet()) {
            parts.add(civ.messages().component(e.getKey().equals(current) ? "structure.theme.current" : "structure.theme.entry",
                    arg("id", e.getKey()), arg("name", e.getValue())));
        }
        civ.messages().sendRaw(p, "structure.theme.list", arg("themes", Component.join(
                net.kyori.adventure.text.JoinConfiguration.separator(Component.text(", ")), parts)));
    }

    private void setTheme(Player p, String theme) throws CivException {
        Town town = module.selectedTown(p);
        CivException.check(module.canManage(p, town), "structure.error.not-manager");
        String id = matchTheme(theme);
        if (id == null) throw new CivException("structure.error.unknown-theme", arg("theme", theme));
        module.setDefaultTheme(town, id);
        civ.messages().send(p, "structure.theme.set", arg("name", module.themes().get(id)));
    }
}
