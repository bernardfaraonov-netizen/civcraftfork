package com.civcraft.structure.command;

import static com.civcraft.core.text.Messages.arg;

import com.civcraft.CivCraft;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.util.BlockPos;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.model.Town;
import com.civcraft.structure.Structure;
import com.civcraft.structure.StructureApi;
import com.civcraft.structure.StructureModule;
import com.civcraft.structure.type.StructureType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /civadmin structure …}: list, info, complete, destroy, remove, rebuild, place, level, reload. */
public final class StructureAdminCommand {

    @FunctionalInterface
    private interface StructureAction {
        void run(CommandSender sender, Structure s, CommandContext<CommandSourceStack> ctx) throws CivException;
    }

    private final StructureModule module;
    private final CivCraft civ;

    public StructureAdminCommand(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Cmd.literal("structure")
                .executes(Cmd.run(ctx -> civ.messages().sendRaw(ctx.getSource().getSender(), "structure.admin.help")))
                .then(Cmd.literal("list")
                        .executes(Cmd.run(ctx -> list(ctx.getSource().getSender(), null)))
                        .then(Cmd.arg("town", StringArgumentType.word())
                                .suggests(Cmd.suggest(() -> civ.state().towns().stream().map(Town::name).toList()))
                                .executes(Cmd.run(ctx -> list(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "town"))))))
                .then(withId("info", (sender, s, ctx) -> info(sender, s)))
                .then(withId("complete", (sender, s, ctx) -> {
                    module.completeNow(s);
                    civ.messages().send(sender, "structure.admin.completed", arg("name", s.typeDef().name()), arg("id", s.id()));
                }))
                .then(withId("destroy", (sender, s, ctx) -> {
                    module.destroy(s, StructureDestroyedEvent.Cause.ADMIN);
                    civ.messages().send(sender, "structure.admin.destroyed", arg("name", s.typeDef().name()), arg("id", s.id()));
                }))
                .then(withId("rebuild", (sender, s, ctx) -> {
                    module.rebuild(s);
                    civ.messages().send(sender, "structure.admin.rebuilt", arg("name", s.typeDef().name()), arg("id", s.id()));
                }))
                .then(Cmd.literal("remove")
                        .then(idArg().executes(Cmd.run(ctx -> remove(ctx, true)))
                                .then(Cmd.literal("keep").executes(Cmd.run(ctx -> remove(ctx, false))))))
                .then(Cmd.literal("level")
                        .then(idArg().then(Cmd.arg("level", IntegerArgumentType.integer(0, 100))
                                .executes(Cmd.run(ctx -> {
                                    Structure s = resolve(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "id"));
                                    module.setLevel(s, IntegerArgumentType.getInteger(ctx, "level"));
                                    civ.messages().send(ctx.getSource().getSender(), "structure.admin.level",
                                            arg("name", s.typeDef().name()), arg("level", s.level()));
                                })))))
                .then(Cmd.literal("place")
                        .then(Cmd.arg("type", StringArgumentType.word()).suggests(Cmd.suggest(this::typeIds))
                                .executes(Cmd.player((p, ctx) -> place(p, StringArgumentType.getString(ctx, "type"), null, false)))
                                .then(Cmd.arg("town", StringArgumentType.word())
                                        .suggests(Cmd.suggest(() -> civ.state().towns().stream().map(Town::name).toList()))
                                        .executes(Cmd.player((p, ctx) -> place(p, StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "town"), false)))
                                        .then(Cmd.literal("instant").executes(Cmd.player((p, ctx) -> place(p,
                                                StringArgumentType.getString(ctx, "type"), StringArgumentType.getString(ctx, "town"), true)))))))
                .then(Cmd.literal("controlpoints").executes(Cmd.run(ctx -> {
                    module.controlPoints().recalculateAll();
                    civ.messages().send(ctx.getSource().getSender(), "structure.admin.control-recalculated");
                })))
                .then(Cmd.literal("reload").executes(Cmd.run(ctx -> {
                    civ.templates().clearCache();
                    civ.messages().send(ctx.getSource().getSender(), "structure.admin.reloaded");
                })));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> idArg() {
        return Cmd.arg("id", StringArgumentType.word()).suggests(Cmd.suggest(this::ids));
    }

    private LiteralArgumentBuilder<CommandSourceStack> withId(String name, StructureAction action) {
        return Cmd.literal(name).then(idArg().executes(Cmd.run(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            action.run(sender, resolve(sender, StringArgumentType.getString(ctx, "id")), ctx);
        })));
    }

    private Collection<String> ids() {
        List<String> ids = new ArrayList<>();
        ids.add("nearest");
        for (Structure s : module.all()) ids.add(s.id());
        return ids;
    }

    private Collection<String> typeIds() {
        List<String> ids = new ArrayList<>();
        for (StructureType t : module.types().all()) if (t.usesTemplate()) ids.add(t.id());
        return ids;
    }

    private Structure resolve(CommandSender sender, String id) throws CivException {
        if (id.equalsIgnoreCase("nearest")) {
            if (!(sender instanceof Player p)) throw new CivException("error.players-only");
            Structure best = null;
            double bestD = Double.MAX_VALUE;
            for (Structure s : module.all()) {
                if (!s.origin().world().equals(p.getWorld().getName())) continue;
                BlockPos c = s.center();
                double d = Math.pow(c.x() - p.getLocation().getX(), 2) + Math.pow(c.y() - p.getLocation().getY(), 2)
                        + Math.pow(c.z() - p.getLocation().getZ(), 2);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            if (best == null) throw new CivException("structure.error.none-nearby");
            return best;
        }
        Structure exact = module.structure(id);
        if (exact != null) return exact;
        Structure found = null;
        for (Structure s : module.all()) {
            if (s.id().startsWith(id.toLowerCase())) {
                if (found != null) throw new CivException("structure.admin.ambiguous-id", arg("id", id));
                found = s;
            }
        }
        if (found == null) throw new CivException("structure.admin.unknown-id", arg("id", id));
        return found;
    }

    private void list(CommandSender sender, String townName) throws CivException {
        Town filter = null;
        if (townName != null) {
            filter = civ.state().townByName(townName);
            if (filter == null) throw new CivException("error.unknown-town", arg("name", townName));
        }
        int n = 0;
        for (Structure s : module.all()) {
            if (filter != null && !filter.id().equals(s.townId())) continue;
            Town t = module.town(s);
            civ.messages().sendRaw(sender, "structure.admin.list-line", arg("id", s.id()), arg("name", s.typeDef().name()),
                    arg("town", t == null ? "?" : t.name()), arg("state", s.state().name().toLowerCase()),
                    arg("percent", (int) Math.floor(s.progress() * 100)), module.coords(s));
            n++;
        }
        civ.messages().sendRaw(sender, "structure.admin.list-total", arg("count", n));
    }

    private void info(CommandSender sender, Structure s) {
        Town t = module.town(s);
        civ.messages().sendRaw(sender, "structure.admin.info", arg("id", s.id()), arg("name", s.typeDef().name()),
                arg("type", s.type()), arg("town", t == null ? "?" : t.name()), arg("state", s.state().name().toLowerCase()),
                arg("hammers", Format.number(Math.floor(s.hammersDone()))), arg("required", Format.number(Math.ceil(s.hammersRequired()))),
                arg("hp", s.hp()), arg("max", s.maxHp()), arg("level", s.level()), arg("theme", s.theme()),
                arg("template", (s.procedural() ? "procedural:" : "") + s.templateId()), arg("rotation", s.rotation().name()),
                arg("size", s.sizeX() + "×" + s.sizeY() + "×" + s.sizeZ()), module.coords(s),
                arg("components", s.components().size()), arg("control", s.controlPoints().size()),
                arg("cursor", s.cursor() + "/" + s.cellCount()));
    }

    private void remove(CommandContext<CommandSourceStack> ctx, boolean restore) throws CivException {
        CommandSender sender = ctx.getSource().getSender();
        Structure s = resolve(sender, StringArgumentType.getString(ctx, "id"));
        module.remove(s, StructureDestroyedEvent.Cause.ADMIN, restore);
        civ.messages().send(sender, "structure.admin.removed", arg("name", s.typeDef().name()), arg("id", s.id()));
    }

    private void place(Player p, String typeId, String townName, boolean instant) throws CivException {
        StructureType type = module.types().get(typeId);
        if (type == null) throw new CivException("structure.error.unknown-type", arg("name", typeId));
        Town town = townName == null ? civ.state().townOf(p) : civ.state().townByName(townName);
        if (town == null) throw new CivException("error.unknown-town", arg("name", townName == null ? "-" : townName));
        StructureApi.Placement pl = module.computePlacement(p.getLocation(), type, module.defaultTheme(town), null, 0);
        StructureApi.Placed placed = module.place(p, town, type.id(), pl.origin(), pl.rotation(), null, instant, true);
        civ.messages().send(p, "structure.admin.placed", arg("name", type.name()), arg("id", placed.id()), arg("town", town.name()));
    }
}
