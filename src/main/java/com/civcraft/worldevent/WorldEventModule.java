package com.civcraft.worldevent;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.Durations;
import com.civcraft.effect.EffectParser;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.Stats;
import com.civcraft.model.Civilization;
import com.civcraft.model.Relation;
import com.civcraft.model.RelationType;
import com.civcraft.randomevent.TownEventApi;
import com.civcraft.storage.Stored;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Weekly world events (spec 04 §13): every Tuesday at 12:00 one random event is drawn and lasts
 * three days for the whole server. Town/civ effects are modifiers in the {@code StatService}; other
 * effects (rats) are exposed through {@link #global(String)}.
 */
public final class WorldEventModule implements Module, WorldEventApi, EffectProvider {

    static final String COLLECTION = "world_event";

    record EventDef(String id, int weight, List<Modifier> effects, Map<String, Double> global,
                    double pacifismAggressor, double pacifismPeaceful) {
    }

    static final class State implements Stored {
        String active;
        Instant started;
        Instant ends;
        String last;
        Instant lastDraw;

        @Override
        public String storageId() {
            return "state";
        }
    }

    private CivCraft civ;
    private final Map<String, EventDef> events = new LinkedHashMap<>();
    private State state = new State();
    private boolean enabled;
    private DayOfWeek day;
    private LocalTime time;
    private Duration duration;
    private boolean avoidRepeat;

    @Override
    public String id() {
        return "worldevents";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("worldevents");
        YamlConfiguration cfg = civ.balance().file("events");
        ConfigurationSection w = cfg.getConfigurationSection("world-events");
        if (w == null) w = new YamlConfiguration();
        enabled = w.getBoolean("enabled", true);
        try {
            day = DayOfWeek.valueOf(w.getString("day", "TUESDAY").toUpperCase(Locale.ROOT));
            time = LocalTime.parse(w.getString("time", "12:00"));
        } catch (RuntimeException e) {
            civ.logger().warning("events.yml: bad world-events day/time, using Tuesday 12:00");
            day = DayOfWeek.TUESDAY;
            time = LocalTime.NOON;
        }
        duration = Duration.ofHours(Math.max(1, w.getInt("duration-hours", 72)));
        avoidRepeat = w.getBoolean("avoid-repeat", true);
        ConfigurationSection list = w.getConfigurationSection("events");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection e = list.getConfigurationSection(id);
                if (e == null) continue;
                Map<String, Double> global = new LinkedHashMap<>();
                ConfigurationSection g = e.getConfigurationSection("global");
                if (g != null) {
                    for (String k : g.getKeys(true)) if (!g.isConfigurationSection(k)) global.put(k, g.getDouble(k));
                }
                events.put(id, new EventDef(id, Math.max(0, e.getInt("weight", 1)),
                        EffectParser.parse(e.getList("effects"), Scope.GLOBAL, "event:" + id), global,
                        e.getDouble("pacifism.aggressor-unhappiness", 0), e.getDouble("pacifism.peaceful-unhappiness", 0)));
            }
        }
        civ.store().createCollection(COLLECTION);
        List<State> loaded = civ.store().loadAll(COLLECTION, State.class);
        if (!loaded.isEmpty()) state = loaded.getFirst();
        if (state.active != null && !events.containsKey(state.active)) state.active = null;
    }

    @Override
    public void enable(CivCraft civ) {
        civ.stats().register(this);
        civ.clock().everyMinute("world-events", this::minute);
        // Pacifism depends on wars; refresh the sheets hourly while it runs.
        civ.clock().hourly(0, "world-events-refresh", () -> {
            if (state.active != null) civ.stats().invalidate();
        });
        civ.tasks().later(20 * 5, this::minute);
        registerCommands();
        registerAdmin();
    }

    // --- schedule ----------------------------------------------------------------------------------

    private ZonedDateTime lastBoundary(ZonedDateTime now) {
        ZonedDateTime b = now.with(TemporalAdjusters.previousOrSame(day)).with(time);
        if (b.isAfter(now)) b = b.minusWeeks(1);
        return b;
    }

    private void minute() {
        Instant now = Instant.now();
        if (state.active != null && state.ends != null && !now.isBefore(state.ends)) end(true);
        if (!enabled) return;
        ZonedDateTime boundary = lastBoundary(civ.clock().now());
        Instant b = boundary.toInstant();
        if (state.lastDraw != null && !b.isAfter(state.lastDraw)) return;
        state.lastDraw = b;
        Instant ends = b.plus(duration);
        if (state.active == null && now.isBefore(ends)) {
            EventDef pick = draw();
            if (pick != null) start(pick, ends);
        }
        civ.saves().save(COLLECTION, state);
    }

    private EventDef draw() {
        List<EventDef> pool = new ArrayList<>();
        int total = 0;
        for (EventDef e : events.values()) {
            if (e.weight() <= 0 || (avoidRepeat && events.size() > 1 && e.id().equals(state.last))) continue;
            pool.add(e);
            total += e.weight();
        }
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (EventDef e : pool) {
            r -= e.weight();
            if (r < 0) return e;
        }
        return pool.getLast();
    }

    private void start(EventDef def, Instant ends) {
        state.active = def.id();
        state.started = Instant.now();
        state.ends = ends;
        state.last = def.id();
        civ.saves().save(COLLECTION, state);
        civ.stats().invalidate();
        Bukkit.getServer().sendMessage(civ.messages().prefix().append(civ.messages().component("worldevents.started",
                Messages.arg("name", name(def.id())),
                Messages.arg("left", Durations.format(Duration.between(Instant.now(), ends))))));
        Bukkit.getServer().sendMessage(civ.messages().component("worldevents.event." + def.id() + ".description"));
        civ.logger().info("World event started: " + def.id() + " until " + ends);
    }

    private void end(boolean announce) {
        String id = state.active;
        state.active = null;
        state.ends = null;
        civ.saves().save(COLLECTION, state);
        civ.stats().invalidate();
        if (announce && id != null) {
            Bukkit.getServer().sendMessage(civ.messages().prefix().append(civ.messages().component("worldevents.ended",
                    Messages.arg("name", name(id)))));
        }
    }

    private String name(String id) {
        return civ.messages().plain("worldevents.event." + id + ".name");
    }

    // --- effects -----------------------------------------------------------------------------------

    @Override
    public void contribute(EffectSink sink) {
        EventDef def = state.active == null ? null : events.get(state.active);
        if (def == null) return;
        var civs = civ.state().civs();
        if (civs.isEmpty()) return;
        Civilization any = civs.iterator().next();
        for (Modifier m : def.effects()) sink.civ(any, m);
        if (def.pacifismAggressor() != 0 || def.pacifismPeaceful() != 0) {
            for (Civilization c : civs) {
                boolean atWar = false;
                boolean aggressor = false;
                for (Relation r : civ.state().relations(c)) {
                    if (r.type() != RelationType.WAR) continue;
                    atWar = true;
                    if (c.id().equals(r.aggressor())) aggressor = true;
                }
                if (aggressor && def.pacifismAggressor() != 0) {
                    sink.civ(c, new Modifier(Stats.UNHAPPINESS, Op.ADD, def.pacifismAggressor(), Scope.CIV, "event:" + def.id()));
                } else if (!atWar && def.pacifismPeaceful() != 0) {
                    sink.civ(c, new Modifier(Stats.UNHAPPINESS, Op.ADD, def.pacifismPeaceful(), Scope.CIV, "event:" + def.id()));
                }
            }
        }
    }

    // --- API ---------------------------------------------------------------------------------------

    @Override
    public String activeEvent() {
        return state.active;
    }

    @Override
    public Instant activeUntil() {
        return state.ends;
    }

    @Override
    public Instant nextDraw() {
        return lastBoundary(civ.clock().now()).plusWeeks(1).toInstant();
    }

    @Override
    public double global(String key) {
        EventDef def = state.active == null ? null : events.get(state.active);
        if (def == null) return 0;
        Double v = def.global().get(key);
        return v == null ? 0 : v;
    }

    @Override
    public void describe(Audience audience) {
        if (state.active == null) {
            civ.messages().send(audience, "worldevents.none",
                    Messages.arg("next", Durations.format(Duration.between(Instant.now(), nextDraw()))));
            return;
        }
        civ.messages().send(audience, "worldevents.current", Messages.arg("name", name(state.active)),
                Messages.arg("left", Durations.format(Duration.between(Instant.now(), state.ends))));
        civ.messages().sendRaw(audience, "worldevents.event." + state.active + ".description");
    }

    // --- commands ----------------------------------------------------------------------------------

    private void registerCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("events").executes(Cmd.run(ctx -> {
            var sender = ctx.getSource().getSender();
            describe(sender);
            TownEventApi towns = civ.apiOrNull(TownEventApi.class);
            if (towns != null && sender instanceof Player p) {
                var town = civ.state().townOf(p);
                if (town != null) towns.describe(sender, town);
            }
        }));
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                e -> e.registrar().register(root.build(), civ.messages().plain("worldevents.command-description"),
                        List.of("event")));
    }

    private void registerAdmin() {
        var ids = Cmd.suggest(events::keySet);
        AdminRegistry.add(Cmd.literal("worldevent")
                .then(Cmd.literal("list").executes(Cmd.run(ctx -> {
                    for (String id : events.keySet()) {
                        civ.messages().sendRaw(ctx.getSource().getSender(), "worldevents.admin.line",
                                Messages.arg("id", id), Messages.arg("name", name(id)));
                    }
                })))
                .then(Cmd.literal("start").then(Cmd.arg("id", StringArgumentType.word()).suggests(ids)
                        .executes(Cmd.run(ctx -> adminStart(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "id"), (int) duration.toHours())))
                        .then(Cmd.arg("hours", IntegerArgumentType.integer(1, 24 * 14))
                                .executes(Cmd.run(ctx -> adminStart(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "hours")))))))
                .then(Cmd.literal("stop").executes(Cmd.run(ctx -> {
                    CivException.check(state.active != null, "worldevents.admin.none");
                    end(true);
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                }))));
    }

    private void adminStart(org.bukkit.command.CommandSender sender, String id, int hours) throws CivException {
        EventDef def = events.get(id);
        CivException.check(def != null, "worldevents.admin.unknown", Messages.arg("id", id));
        if (state.active != null) end(true);
        start(def, Instant.now().plus(Duration.ofHours(hours)));
        civ.messages().send(sender, "pve.admin.done");
    }
}
