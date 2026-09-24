package com.civcraft.randomevent;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.clock.GameClock;
import com.civcraft.command.AdminRegistry;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Durations;
import com.civcraft.core.util.Money;
import com.civcraft.effect.EffectParser;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Scope;
import com.civcraft.model.Civilization;
import com.civcraft.model.Claim;
import com.civcraft.model.Town;
import com.civcraft.pve.PveKeys;
import com.civcraft.storage.Stored;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Random town events (spec 04 §14). Once a day, after the tax tick, every town without an event
 * gets one with the configured chance (not in the first days of the phase). A town has at most one
 * event, effects never stack with earlier ones. Kinds: timed effects (Truffles, Black soil, Fair...),
 * the Plague (kill the slimes in time or get unhappiness) and the Gold rush (find the treasure block).
 * All state is persisted; everything runs on the main thread.
 */
public final class RandomEventModule implements Module, TownEventApi, EffectProvider, Listener {

    static final String COLLECTION = "town_events";
    static final String META = "town_events_meta";

    static final class TownEvent implements Stored {
        String townId;
        String type;
        /** "active" (plague/treasure running) or "effect" (modifiers apply). */
        String phase;
        /** Which list of the definition applies in the effect phase: "effects" or "penalty". */
        String effectKey = "effects";
        Instant started;
        Instant until;
        int total;
        int killed;
        boolean spawned;
        List<String> slimes = new ArrayList<>();
        BlockPos treasure;
        boolean placed;
        String original;

        @Override
        public String storageId() {
            return townId;
        }
    }

    static final class Meta implements Stored {
        Instant firstSeen;
        /** Treasure blocks to restore when their chunk loads: "pos|material". */
        List<String> cleanup = new ArrayList<>();

        @Override
        public String storageId() {
            return "meta";
        }
    }

    private CivCraft civ;
    private final Map<String, ConfigurationSection> defs = new LinkedHashMap<>();
    private final Map<String, List<Modifier>> effectCache = new HashMap<>();
    private final Map<String, TownEvent> events = new HashMap<>();
    private Meta meta = new Meta();
    private ConfigurationSection cfg;

    @Override
    public String id() {
        return "randomevents";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("randomevents");
        cfg = civ.balance().file("events").getConfigurationSection("random-events");
        if (cfg == null) cfg = new YamlConfiguration();
        ConfigurationSection list = cfg.getConfigurationSection("events");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(id);
                if (s != null) defs.put(id, s);
            }
        }
        civ.store().createCollection(COLLECTION);
        civ.store().createCollection(META);
        for (TownEvent e : civ.store().loadAll(COLLECTION, TownEvent.class)) {
            if (e.townId != null && defs.containsKey(e.type)) events.put(e.townId, e);
        }
        List<Meta> m = civ.store().loadAll(META, Meta.class);
        if (!m.isEmpty()) meta = m.getFirst();
        if (meta.cleanup == null) meta.cleanup = new ArrayList<>();
        if (meta.firstSeen == null) {
            meta.firstSeen = Instant.now();
            civ.saves().save(META, meta);
        }
    }

    @Override
    public void enable(CivCraft civ) {
        civ.stats().register(this);
        civ.listen(this);
        civ.clock().daily(GameClock.CONSEQUENCES + 50, "random-events", this::daily);
        civ.clock().everyMinute("random-events", this::minute);
        registerAdmin();
    }

    private List<Modifier> modifiers(String type, String key) {
        return effectCache.computeIfAbsent(type + "/" + key, k -> {
            ConfigurationSection d = defs.get(type);
            return d == null ? List.of() : EffectParser.parse(d.getList(key), Scope.TOWN, "random-event:" + type);
        });
    }

    // --- scheduling --------------------------------------------------------------------------------

    private Instant phaseStart() {
        String configured = cfg.getString("phase-start", "");
        if (configured != null && !configured.isBlank()) {
            try {
                return LocalDate.parse(configured).atStartOfDay(civ.clock().zone()).toInstant();
            } catch (RuntimeException e) {
                civ.logger().warning("events.yml: bad random-events.phase-start '" + configured + "'");
            }
        }
        return meta.firstSeen;
    }

    private void daily() {
        if (!cfg.getBoolean("enabled", true)) return;
        Instant earliest = phaseStart().plus(Duration.ofDays(Math.max(0, cfg.getInt("first-after-days", 3))));
        if (Instant.now().isBefore(earliest)) return;
        double chance = cfg.getDouble("chance", 50);
        for (Town town : List.copyOf(civ.state().towns())) {
            if (events.containsKey(town.id())) continue;
            if (ThreadLocalRandom.current().nextDouble(100) >= chance) continue;
            String type = draw();
            if (type != null) start(town, type);
        }
    }

    private String draw() {
        int total = 0;
        for (ConfigurationSection d : defs.values()) total += Math.max(0, d.getInt("weight", 1));
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, ConfigurationSection> e : defs.entrySet()) {
            r -= Math.max(0, e.getValue().getInt("weight", 1));
            if (r < 0) return e.getKey();
        }
        return null;
    }

    private String kind(String type) {
        ConfigurationSection d = defs.get(type);
        return d == null ? "effect" : d.getString("kind", "effect");
    }

    void start(Town town, String type) {
        ConfigurationSection d = defs.get(type);
        TownEvent e = new TownEvent();
        e.townId = town.id();
        e.type = type;
        e.started = Instant.now();
        switch (kind(type)) {
            case "plague" -> {
                e.phase = "active";
                e.total = Math.max(1, d.getInt("slimes", 20));
                e.until = Instant.now().plus(hours(d.getDouble("spawn-window-hours", 24)));
            }
            case "treasure" -> {
                e.phase = "active";
                e.until = Instant.now().plus(hours(d.getDouble("search-hours", 24)));
                e.treasure = pickTreasure(town, d);
                if (e.treasure == null) return;
            }
            default -> {
                e.phase = "effect";
                e.until = Instant.now().plus(hours(d.getDouble("hours", 72)));
            }
        }
        events.put(town.id(), e);
        save(e);
        civ.stats().invalidate();
        tellTown(town, "randomevents.started", Messages.arg("name", name(type)));
        tellTown(town, "randomevents.event." + type + ".description");
        if (e.treasure != null) {
            tryPlaceTreasure(e);
            tellTown(town, "randomevents.treasure.hint", hintArgs(e));
        }
        if ("plague".equals(kind(type))) trySpawnSlimes(town, e);
    }

    private static Duration hours(double h) {
        return Duration.ofMinutes(Math.max(1, Math.round(h * 60)));
    }

    private void save(TownEvent e) {
        civ.saves().save(COLLECTION, e);
    }

    private void finish(TownEvent e) {
        events.remove(e.townId);
        civ.saves().delete(COLLECTION, e.townId);
        civ.stats().invalidate();
    }

    private void minute() {
        Instant now = Instant.now();
        for (TownEvent e : List.copyOf(events.values())) {
            Town town = civ.state().town(e.townId);
            if (town == null) {
                removeSlimes(e);
                removeTreasure(e);
                finish(e);
                continue;
            }
            String kind = kind(e.type);
            if ("active".equals(e.phase) && "plague".equals(kind) && !e.spawned) trySpawnSlimes(town, e);
            if (now.isBefore(e.until)) continue;
            if ("effect".equals(e.phase)) {
                tellTown(town, "randomevents.ended", Messages.arg("name", name(e.type)));
                finish(e);
            } else if ("plague".equals(kind)) {
                endPlague(town, e);
            } else if ("treasure".equals(kind)) {
                removeTreasure(e);
                tellTown(town, "randomevents.treasure.failed");
                finish(e);
            } else {
                finish(e);
            }
        }
    }

    // --- plague ------------------------------------------------------------------------------------

    private int needed(TownEvent e) {
        double pct = defs.get(e.type).getDouble("kill-percent", 80);
        return (int) Math.ceil(e.total * Math.max(0, Math.min(100, pct)) / 100.0);
    }

    private void trySpawnSlimes(Town town, TownEvent e) {
        ConfigurationSection d = defs.get(e.type);
        List<Claim> claims = new ArrayList<>(civ.state().claims(town));
        List<Location> spots = new ArrayList<>();
        for (Claim c : claims) {
            ChunkKey k = c.chunk();
            World w = Bukkit.getWorld(k.world());
            if (w == null || !w.isChunkLoaded(k.x(), k.z())) continue;
            int x = (k.x() << 4) + ThreadLocalRandom.current().nextInt(16);
            int z = (k.z() << 4) + ThreadLocalRandom.current().nextInt(16);
            spots.add(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5));
        }
        if (spots.isEmpty()) return;
        int size = Math.max(1, d.getInt("slime-size", 2));
        for (int i = 0; i < e.total; i++) {
            Location at = spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
            Slime slime = at.getWorld().spawn(at, Slime.class, CreatureSpawnEvent.SpawnReason.CUSTOM, s -> {
                s.setSize(size);
                s.setRemoveWhenFarAway(false);
                s.getPersistentDataContainer().set(PveKeys.PLAGUE_TOWN, PersistentDataType.STRING, town.id());
            });
            e.slimes.add(slime.getUniqueId().toString());
        }
        e.spawned = true;
        e.until = Instant.now().plus(hours(d.getDouble("kill-hours", 1)));
        save(e);
        tellTown(town, "randomevents.plague.spawned", Messages.arg("count", e.total), Messages.arg("need", needed(e)),
                Messages.arg("time", Durations.format(Duration.between(Instant.now(), e.until))));
    }

    private void endPlague(Town town, TownEvent e) {
        removeSlimes(e);
        if (!e.spawned) {
            tellTown(town, "randomevents.plague.passed");
            finish(e);
        } else if (e.killed >= needed(e)) {
            tellTown(town, "randomevents.plague.success");
            finish(e);
        } else {
            ConfigurationSection d = defs.get(e.type);
            e.phase = "effect";
            e.effectKey = "penalty";
            e.until = Instant.now().plus(hours(d.getDouble("penalty-hours", 48)));
            save(e);
            civ.stats().invalidate();
            tellTown(town, "randomevents.plague.failed",
                    Messages.arg("time", Durations.format(Duration.between(Instant.now(), e.until))));
        }
    }

    private void removeSlimes(TownEvent e) {
        for (String id : e.slimes) {
            Entity s = Bukkit.getEntity(UUID.fromString(id));
            if (s != null) s.remove();
        }
        e.slimes.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(PveKeys.PLAGUE_TOWN)) event.setCancelled(true);
    }

    @EventHandler
    public void onSlimeDeath(EntityDeathEvent event) {
        String townId = event.getEntity().getPersistentDataContainer().get(PveKeys.PLAGUE_TOWN, PersistentDataType.STRING);
        if (townId == null) return;
        event.getDrops().clear();
        TownEvent e = events.get(townId);
        if (e == null || !"active".equals(e.phase) || !e.slimes.remove(event.getEntity().getUniqueId().toString())) return;
        if (event.getEntity().getKiller() == null) {
            save(e);
            return;
        }
        e.killed++;
        save(e);
        Town town = civ.state().town(townId);
        if (town == null) return;
        if (e.killed >= needed(e)) {
            endPlague(town, e);
        } else if (e.killed % 5 == 0) {
            tellTown(town, "randomevents.plague.progress", Messages.arg("killed", e.killed), Messages.arg("need", needed(e)));
        }
    }

    @EventHandler
    public void onSlimeLoad(EntityAddToWorldEvent event) {
        Entity en = event.getEntity();
        String townId = en.getPersistentDataContainer().get(PveKeys.PLAGUE_TOWN, PersistentDataType.STRING);
        if (townId == null) return;
        TownEvent e = events.get(townId);
        if (e == null || !"active".equals(e.phase) || !e.slimes.contains(en.getUniqueId().toString())) {
            civ.tasks().nextTick(en::remove);
        }
    }

    // --- treasure ----------------------------------------------------------------------------------

    private BlockPos pickTreasure(Town town, ConfigurationSection d) {
        BlockPos center = town.center();
        if (center == null) return null;
        World w = Bukkit.getWorld(center.world());
        if (w == null) return null;
        int min = Math.max(1, d.getInt("min-distance-chunks", 8));
        int max = Math.max(min + 1, d.getInt("max-distance-chunks", 40));
        int yMin = Math.max(w.getMinHeight() + 1, d.getInt("y-min", -40));
        int yMax = Math.min(w.getMaxHeight() - 1, Math.max(yMin, d.getInt("y-max", 40)));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = r.nextDouble(Math.PI * 2);
            int dist = r.nextInt(min, max + 1);
            int cx = (center.x() >> 4) + (int) Math.round(Math.cos(angle) * dist);
            int cz = (center.z() >> 4) + (int) Math.round(Math.sin(angle) * dist);
            if (civ.state().claim(new ChunkKey(w.getName(), cx, cz)) != null) continue;
            if (!w.getWorldBorder().isInside(new Location(w, (cx << 4) + 8, 64, (cz << 4) + 8))) continue;
            return new BlockPos(w.getName(), (cx << 4) + r.nextInt(16), r.nextInt(yMin, yMax + 1), (cz << 4) + r.nextInt(16));
        }
        return null;
    }

    private Material treasureMaterial(TownEvent e) {
        Material m = Material.matchMaterial(defs.get(e.type).getString("block", "RAW_GOLD_BLOCK"));
        return m != null && m.isBlock() ? m : Material.RAW_GOLD_BLOCK;
    }

    private void tryPlaceTreasure(TownEvent e) {
        if (e.placed || e.treasure == null) return;
        World w = e.treasure.bukkitWorld();
        if (w == null || !w.isChunkLoaded(e.treasure.x() >> 4, e.treasure.z() >> 4)) return;
        Block b = e.treasure.block();
        e.original = b.getType().name();
        b.setType(treasureMaterial(e), false);
        e.placed = true;
        save(e);
    }

    private void removeTreasure(TownEvent e) {
        if (e.treasure == null || !e.placed) return;
        World w = e.treasure.bukkitWorld();
        Material original = e.original == null ? Material.STONE : Material.matchMaterial(e.original);
        if (original == null) original = Material.STONE;
        if (w != null && w.isChunkLoaded(e.treasure.x() >> 4, e.treasure.z() >> 4)) {
            Block b = e.treasure.block();
            if (b.getType() == treasureMaterial(e)) b.setType(original, false);
        } else {
            meta.cleanup.add(e.treasure + "|" + original.name() + "|" + treasureMaterial(e).name());
            civ.saves().save(META, meta);
        }
        e.placed = false;
    }

    private TownEvent treasureAt(Block block) {
        for (TownEvent e : events.values()) {
            if (e.treasure != null && e.placed && "active".equals(e.phase) && e.treasure.equals(BlockPos.of(block))) return e;
        }
        return null;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkKey key = ChunkKey.of(event.getChunk());
        for (TownEvent e : events.values()) {
            if (e.treasure != null && !e.placed && "active".equals(e.phase) && e.treasure.chunk().equals(key)) {
                civ.tasks().nextTick(() -> tryPlaceTreasure(e));
            }
        }
        if (meta.cleanup.isEmpty()) return;
        for (String entry : List.copyOf(meta.cleanup)) {
            String[] p = entry.split("\\|");
            if (p.length != 3) {
                meta.cleanup.remove(entry);
                continue;
            }
            BlockPos pos;
            try {
                pos = BlockPos.parse(p[0]);
            } catch (RuntimeException ex) {
                meta.cleanup.remove(entry);
                continue;
            }
            if (!pos.chunk().equals(key)) continue;
            meta.cleanup.remove(entry);
            civ.saves().save(META, meta);
            civ.tasks().nextTick(() -> {
                Block b = pos.block();
                Material placed = Material.matchMaterial(p[2]);
                Material original = Material.matchMaterial(p[1]);
                if (b != null && b.getType() == placed && original != null) b.setType(original, false);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TownEvent e = treasureAt(event.getBlock());
        if (e == null) return;
        Player player = event.getPlayer();
        Town town = civ.state().town(e.townId);
        if (town == null || !town.isResident(player.getUniqueId())) {
            event.setCancelled(true);
            civ.messages().send(player, "randomevents.treasure.not-yours");
            return;
        }
        event.setDropItems(false);
        long reward = Money.ofCoins(Math.max(0, defs.get(e.type).getDouble("reward", 75000)));
        town.addTreasury(reward);
        civ.state().save(town);
        e.placed = false;
        tellTown(town, "randomevents.treasure.found", Messages.arg("player", player.getName()), Messages.money("coins", reward));
        finish(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> treasureAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> treasureAt(b) != null);
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] hintArgs(TownEvent e) {
        int cx = e.treasure.x() >> 4;
        int cz = e.treasure.z() >> 4;
        int spread = Math.max(0, defs.get(e.type).getInt("hint-y-spread", 8));
        return new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Messages.arg("x1", cx << 4), Messages.arg("x2", (cx << 4) + 15),
                Messages.arg("z1", cz << 4), Messages.arg("z2", (cz << 4) + 15),
                Messages.arg("y1", e.treasure.y() - spread), Messages.arg("y2", e.treasure.y() + spread),
                Messages.arg("time", Durations.format(Duration.between(Instant.now(), e.until)))};
    }

    // --- effects -----------------------------------------------------------------------------------

    @Override
    public void contribute(EffectSink sink) {
        for (TownEvent e : events.values()) {
            if (!"effect".equals(e.phase)) continue;
            Town town = civ.state().town(e.townId);
            if (town == null) continue;
            for (Modifier m : modifiers(e.type, e.effectKey)) sink.town(town, m);
        }
    }

    // --- API ---------------------------------------------------------------------------------------

    @Override
    public String activeEvent(Town town) {
        TownEvent e = events.get(town.id());
        return e == null ? null : e.type;
    }

    @Override
    public Instant activeUntil(Town town) {
        TownEvent e = events.get(town.id());
        return e == null ? null : e.until;
    }

    @Override
    public void describe(Audience audience, Town town) {
        TownEvent e = events.get(town.id());
        if (e == null) {
            civ.messages().send(audience, "randomevents.none", Messages.arg("town", town.name()));
            return;
        }
        String left = Durations.format(Duration.between(Instant.now(), e.until));
        civ.messages().send(audience, "randomevents.current", Messages.arg("town", town.name()),
                Messages.arg("name", name(e.type)), Messages.arg("left", left));
        civ.messages().sendRaw(audience, "randomevents.event." + e.type + ".description");
        String kind = kind(e.type);
        if ("plague".equals(kind) && "active".equals(e.phase)) {
            civ.messages().sendRaw(audience, e.spawned ? "randomevents.plague.status" : "randomevents.plague.waiting",
                    Messages.arg("killed", e.killed), Messages.arg("need", needed(e)), Messages.arg("time", left));
        } else if ("plague".equals(kind)) {
            civ.messages().sendRaw(audience, "randomevents.plague.penalty", Messages.arg("time", left));
        } else if ("treasure".equals(kind) && e.treasure != null) {
            civ.messages().sendRaw(audience, "randomevents.treasure.hint", hintArgs(e));
        }
    }

    @Override
    public void describe(Audience audience, Civilization c) {
        boolean any = false;
        for (Town t : civ.state().towns(c)) {
            TownEvent e = events.get(t.id());
            if (e == null) continue;
            any = true;
            civ.messages().sendRaw(audience, "randomevents.civ-line", Messages.arg("town", t.name()),
                    Messages.arg("name", name(e.type)),
                    Messages.arg("left", Durations.format(Duration.between(Instant.now(), e.until))));
        }
        if (!any) civ.messages().send(audience, "randomevents.civ-none");
    }

    private String name(String type) {
        return civ.messages().plain("randomevents.event." + type + ".name");
    }

    private void tellTown(Town town, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... args) {
        for (UUID id : town.residents()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) civ.messages().send(p, key, args);
        }
    }

    // --- admin -------------------------------------------------------------------------------------

    private void registerAdmin() {
        var towns = Cmd.suggest(() -> civ.state().towns().stream().map(Town::name).toList());
        AdminRegistry.add(Cmd.literal("townevent")
                .then(Cmd.literal("start").then(Cmd.arg("town", StringArgumentType.word()).suggests(towns)
                        .then(Cmd.arg("event", StringArgumentType.word()).suggests(Cmd.suggest(defs::keySet))
                                .executes(Cmd.run(ctx -> {
                                    Town town = town(StringArgumentType.getString(ctx, "town"));
                                    String type = StringArgumentType.getString(ctx, "event");
                                    CivException.check(defs.containsKey(type), "randomevents.admin.unknown",
                                            Messages.arg("id", type));
                                    TownEvent old = events.get(town.id());
                                    if (old != null) {
                                        removeSlimes(old);
                                        removeTreasure(old);
                                        finish(old);
                                    }
                                    start(town, type);
                                    CivException.check(events.containsKey(town.id()), "randomevents.admin.failed");
                                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                                })))))
                .then(Cmd.literal("stop").then(Cmd.arg("town", StringArgumentType.word()).suggests(towns)
                        .executes(Cmd.run(ctx -> {
                            Town town = town(StringArgumentType.getString(ctx, "town"));
                            TownEvent e = events.get(town.id());
                            CivException.check(e != null, "randomevents.admin.none");
                            removeSlimes(e);
                            removeTreasure(e);
                            finish(e);
                            civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                        }))))
                .then(Cmd.literal("rollnow").executes(Cmd.run(ctx -> {
                    daily();
                    civ.messages().send(ctx.getSource().getSender(), "pve.admin.done");
                }))));
    }

    private Town town(String name) throws CivException {
        Town t = civ.state().townByName(name);
        if (t == null) throw new CivException("error.unknown-town", Messages.arg("name", name));
        return t;
    }
}
