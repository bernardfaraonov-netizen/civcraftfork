package com.civcraft.boss;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Money;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.pve.AreaService;
import com.civcraft.pve.Give;
import com.civcraft.pve.ItemSpec;
import com.civcraft.pve.PveArea;
import com.civcraft.pve.PveKeys;
import com.civcraft.science.ResearchApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * The Lord of the Air valley (spec 04 §11.2): spawns on a schedule at the boss marker, 800 HP with a
 * boss bar, regenerates, lives one hour. Only valley weapons of players in a civilization hurt it
 * (sword 2, bow 4, +1 per Heavenly punishment level). On death the coins are split by damage among
 * the members of the civilization that landed the last hit; the top damager of that civilization gets
 * the loot.
 */
final class WorldBoss implements Listener {

    private static final NamespacedKey ARROW_SMITE = new NamespacedKey("civcraft", "arrow_smite");

    private final CivCraft civ;
    private final AreaService areas;
    private final ValleyStats stats;
    private final ConfigurationSection cfg;
    private final List<LocalTime> times = new ArrayList<>();
    private final Set<String> swordIds = new HashSet<>();
    private final Set<String> bowIds = new HashSet<>();
    private final NamespacedKey smiteKey;

    private LivingEntity boss;
    private Instant despawnAt;
    private final Map<UUID, Double> damage = new HashMap<>();
    private UUID lastHitter;
    private final Set<UUID> viewers = new HashSet<>();
    private BossBar bar;
    private String lastSpawnSlot;
    private int regenCounter;
    private int aoeCounter;

    WorldBoss(CivCraft civ, AreaService areas, ValleyStats stats, ConfigurationSection cfg) {
        this.civ = civ;
        this.areas = areas;
        this.stats = stats;
        this.cfg = cfg;
        for (String t : cfg.getStringList("spawn-times")) {
            try {
                times.add(LocalTime.parse(t));
            } catch (RuntimeException e) {
                civ.logger().warning("valley.yml: bad boss spawn time '" + t + "'");
            }
        }
        times.sort(null);
        swordIds.addAll(cfg.getStringList("sword-ids"));
        bowIds.addAll(cfg.getStringList("bow-ids"));
        NamespacedKey k = NamespacedKey.fromString(cfg.getString("smite-key", "civcraft:heavenly_punishment"));
        smiteKey = k == null ? new NamespacedKey("civcraft", "heavenly_punishment") : k;
    }

    List<LocalTime> times() {
        return times;
    }

    boolean alive() {
        return boss != null && boss.isValid() && !boss.isDead();
    }

    Instant nextSpawn() {
        if (times.isEmpty()) return null;
        ZonedDateTime now = civ.clock().now();
        for (int day = 0; day <= 1; day++) {
            for (LocalTime t : times) {
                ZonedDateTime at = now.toLocalDate().plusDays(day).atTime(t).atZone(now.getZone());
                if (at.isAfter(now)) return at.toInstant();
            }
        }
        return null;
    }

    /** Whether valley kills/deaths are counted now: from N minutes before a spawn until the boss is gone. */
    boolean counting() {
        if (alive()) return true;
        Instant next = nextSpawn();
        return next != null && Duration.between(Instant.now(), next).toMinutes() < cfg.getInt("stats-lead-minutes", 15);
    }

    /** Clock job, every minute: scheduled spawn. */
    void minute() {
        ZonedDateTime now = civ.clock().now();
        LocalTime current = now.toLocalTime().withSecond(0).withNano(0);
        if (!times.contains(current)) return;
        String slot = now.toLocalDate() + "T" + current;
        if (slot.equals(lastSpawnSlot)) return;
        lastSpawnSlot = slot;
        if (!alive()) spawn();
    }

    /** Spawns the boss at the marker; returns false if the valley or the marker is not ready. */
    boolean spawn() {
        PveArea area = areas.area(ValleyModule.AREA);
        if (area == null || area.world() == null || area.markers("boss").isEmpty()) {
            civ.logger().warning("World boss not spawned: the valley or its 'boss' marker is not configured");
            return false;
        }
        BlockPos pos = area.markers("boss").getFirst();
        World world = area.world();
        Location at = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        world.getChunkAt(at).load();
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(cfg.getString("entity", "wither_skeleton")));
        if (type == null || !type.isAlive()) type = EntityType.WITHER_SKELETON;
        damage.clear();
        lastHitter = null;
        Entity e = world.spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> configure((LivingEntity) en));
        if (!(e instanceof LivingEntity living) || !e.isValid()) return false;
        boss = living;
        despawnAt = Instant.now().plus(Duration.ofMinutes(Math.max(1, cfg.getInt("lifetime-minutes", 60))));
        bar = BossBar.bossBar(civ.messages().component("valley.boss.bar"), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        broadcast("valley.boss.spawned");
        return true;
    }

    private void configure(LivingEntity e) {
        e.getPersistentDataContainer().set(PveKeys.BOSS, PersistentDataType.BYTE, (byte) 1);
        double hp = Math.max(1, cfg.getDouble("hp", 800));
        set(e, Attribute.MAX_HEALTH, hp);
        set(e, Attribute.ATTACK_DAMAGE, cfg.getDouble("damage", 3));
        set(e, Attribute.ARMOR, 0);
        set(e, Attribute.ARMOR_TOUGHNESS, 0);
        set(e, Attribute.KNOCKBACK_RESISTANCE, 1);
        set(e, Attribute.FOLLOW_RANGE, cfg.getDouble("follow-range", 32));
        double scale = cfg.getDouble("scale", 2);
        if (scale > 0) set(e, Attribute.SCALE, scale);
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getDefaultValue() * cfg.getDouble("speed", 1));
        e.setHealth(hp);
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        if (e.getEquipment() != null) e.getEquipment().clear();
        e.customName(civ.messages().component("valley.boss.name"));
        e.setCustomNameVisible(true);
    }

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** Clock job, every second: boss bar, regeneration, AoE, leash, lifetime. */
    void second() {
        if (boss == null) return;
        if (!alive()) {
            cleanup();
            return;
        }
        if (Instant.now().isAfter(despawnAt)) {
            broadcast("valley.boss.left");
            boss.remove();
            cleanup();
            return;
        }
        PveArea area = areas.area(ValleyModule.AREA);
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max == null ? cfg.getDouble("hp", 800) : max.getValue();
        int every = Math.max(1, cfg.getInt("regen.every-seconds", 5));
        if (++regenCounter >= every) {
            regenCounter = 0;
            boss.setHealth(Math.min(maxHp, boss.getHealth() + Math.max(0, cfg.getDouble("regen.amount", 1))));
        }
        bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / maxHp)));
        bar.name(civ.messages().component("valley.boss.bar-hp",
                Messages.arg("hp", (int) Math.ceil(boss.getHealth())), Messages.arg("max", (int) Math.ceil(maxHp))));
        boss.customName(civ.messages().component("valley.boss.name-hp",
                Messages.arg("hp", (int) Math.ceil(boss.getHealth())), Messages.arg("max", (int) Math.ceil(maxHp))));
        updateViewers(area);
        // Keep the boss in its arena.
        if (area != null && !area.markers("boss").isEmpty()) {
            BlockPos home = area.markers("boss").getFirst();
            double leash = cfg.getDouble("leash-radius", 40);
            if (boss.getLocation().distanceSquared(home.center()) > leash * leash) {
                boss.teleport(home.center());
            }
        }
        int aoeEvery = cfg.getInt("aoe.every-seconds", 12);
        if (aoeEvery > 0 && ++aoeCounter >= aoeEvery) {
            aoeCounter = 0;
            slam();
        }
    }

    private void slam() {
        double radius = cfg.getDouble("aoe.radius", 6);
        double dmg = cfg.getDouble("aoe.damage", 3);
        double push = cfg.getDouble("aoe.knockback", 1.6);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.8f);
        DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(boss).withDirectEntity(boss).build();
        for (Player p : boss.getLocation().getNearbyPlayers(radius)) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (areas.zone(p) == PveArea.ZoneType.SAFE) continue;
            p.damage(dmg, source);
            Vector v = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
            if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
            p.setVelocity(v.normalize().multiply(push).setY(0.5));
        }
    }

    private void updateViewers(PveArea area) {
        Set<UUID> now = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (area != null && area.isIn(p.getWorld())) now.add(p.getUniqueId());
        }
        for (UUID id : Set.copyOf(viewers)) {
            if (!now.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
                viewers.remove(id);
            }
        }
        for (UUID id : now) {
            if (viewers.add(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    private void cleanup() {
        if (bar != null) {
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
            }
        }
        viewers.clear();
        boss = null;
        bar = null;
        damage.clear();
        lastHitter = null;
    }

    void remove() {
        if (boss != null) boss.remove();
        cleanup();
    }

    private void broadcast(String key) {
        Bukkit.getServer().sendMessage(civ.messages().prefix().append(civ.messages().component(key)));
    }

    private boolean isBoss(Entity e) {
        return e != null && e.getPersistentDataContainer().has(PveKeys.BOSS);
    }

    // --- events ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getBow() == null || !event.getBow().hasItemMeta()) return;
        Integer smite = event.getBow().getItemMeta().getPersistentDataContainer().get(smiteKey, PersistentDataType.INTEGER);
        if (smite != null && smite > 0) {
            event.getProjectile().getPersistentDataContainer().set(ARROW_SMITE, PersistentDataType.INTEGER, smite);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHurt(EntityDamageByEntityEvent event) {
        if (!isBoss(event.getEntity())) {
            if (isBoss(event.getDamager()) && event.getEntity() instanceof Player) {
                event.setDamage(cfg.getDouble("damage", 3));
            }
            return;
        }
        Player player = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p2 ? p2 : null;
        if (player == null) {
            event.setCancelled(true);
            return;
        }
        Resident r = civ.state().resident(player);
        Civilization c = r == null ? null : civ.state().civOf(r);
        if (cfg.getBoolean("require-civ", true) && c == null) {
            event.setCancelled(true);
            civ.messages().actionBar(player, "valley.boss.need-civ");
            return;
        }
        double dmg;
        int smite;
        if (event.getDamager() instanceof Projectile proj) {
            String bow = proj.getPersistentDataContainer().get(PveKeys.ARROW_BOW, PersistentDataType.STRING);
            if (bow == null || !bowIds.contains(bow)) {
                event.setCancelled(true);
                civ.messages().actionBar(player, "valley.boss.need-weapon");
                return;
            }
            dmg = cfg.getDouble("bow-damage", 4);
            smite = proj.getPersistentDataContainer().getOrDefault(ARROW_SMITE, PersistentDataType.INTEGER, 0);
        } else {
            ItemStack hand = player.getInventory().getItemInMainHand();
            String id = ItemSpec.customId(hand);
            if (id == null || !swordIds.contains(id)) {
                event.setCancelled(true);
                civ.messages().actionBar(player, "valley.boss.need-weapon");
                return;
            }
            dmg = cfg.getDouble("sword-damage", 2);
            smite = hand.hasItemMeta()
                    ? hand.getItemMeta().getPersistentDataContainer().getOrDefault(smiteKey, PersistentDataType.INTEGER, 0) : 0;
        }
        dmg += Math.max(0, smite) * cfg.getDouble("smite-bonus-per-level", 1);
        event.setDamage(dmg);
        double dealt = Math.min(dmg, boss == null ? dmg : boss.getHealth());
        damage.merge(player.getUniqueId(), dealt, Double::sum);
        lastHitter = player.getUniqueId();
        if (counting()) stats.damage(player.getUniqueId(), player.getName(), dealt);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        if (!isBoss(event.getEntity()) || event instanceof EntityDamageByEntityEvent) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.KILL) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossEffect(EntityPotionEffectEvent event) {
        // The wither skeleton model must not apply Wither; the boss itself ignores potions.
        if (isBoss(event.getEntity()) && event.getAction() == EntityPotionEffectEvent.Action.ADDED) {
            event.setCancelled(true);
        } else if (event.getCause() == EntityPotionEffectEvent.Cause.ATTACK && event.getEntity() instanceof Player p
                && p.getLastDamageCause() != null && p.getLastDamageCause().getDamageSource().getCausingEntity() != null
                && isBoss(p.getLastDamageCause().getDamageSource().getCausingEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (isBoss(event.getEntity()) && event.getTarget() instanceof Player p && areas.zone(p) == PveArea.ZoneType.SAFE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        if (!isBoss(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (event.getEntity() != boss) return;
        Location where = boss.getLocation();
        reward(where);
        cleanup();
    }

    // --- reward ------------------------------------------------------------------------------------

    private void reward(Location where) {
        Resident killer = lastHitter == null ? null : civ.state().resident(lastHitter);
        Civilization killerCiv = killer == null ? null : civ.state().civOf(killer);
        if (killerCiv == null) {
            broadcast("valley.boss.died-nobody");
            return;
        }
        stats.win(killerCiv.id(), killerCiv.name());
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        int era = research == null ? 0 : Math.max(0, research.era(killerCiv));
        long total = Money.ofCoins(cfg.getDouble("reward.base-coins", 100000) + cfg.getDouble("reward.coins-per-era", 25000) * era);
        Map<UUID, Double> share = new HashMap<>();
        double sum = 0;
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            Resident r = civ.state().resident(e.getKey());
            if (r == null || civ.state().civOf(r) != killerCiv) continue;
            share.put(e.getKey(), e.getValue());
            sum += e.getValue();
        }
        UUID top = null;
        long paid = 0;
        for (Map.Entry<UUID, Double> e : share.entrySet()) {
            if (top == null || e.getValue() > share.get(top)) top = e.getKey();
            long cents = sum <= 0 ? 0 : (long) Math.floor(total * (e.getValue() / sum));
            pay(e.getKey(), cents, e.getValue() / sum);
            paid += cents;
        }
        if (top != null && total - paid > 0) pay(top, total - paid, -1);
        Bukkit.getServer().sendMessage(civ.messages().prefix().append(civ.messages().component("valley.boss.died",
                Messages.arg("civ", killerCiv.name()), Messages.money("coins", total))));
        if (top == null) return;
        List<ItemStack> loot = new ArrayList<>();
        for (ItemSpec spec : ItemSpec.parseList(cfg.getList("reward.guaranteed"), civ.logger(), "valley.yml reward.guaranteed")) {
            ItemStack s = spec.build();
            if (s != null) loot.add(s);
        }
        List<ItemSpec> oneOf = ItemSpec.parseList(cfg.getList("reward.one-of"), civ.logger(), "valley.yml reward.one-of");
        if (!oneOf.isEmpty()) {
            ItemStack s = oneOf.get(ThreadLocalRandom.current().nextInt(oneOf.size())).build();
            if (s != null) loot.add(s);
        }
        Player winner = Bukkit.getPlayer(top);
        Resident winnerRes = civ.state().resident(top);
        String name = winnerRes == null ? "?" : winnerRes.name();
        if (winner == null) {
            for (ItemStack s : loot) where.getWorld().dropItemNaturally(where, s);
            broadcast2("valley.boss.loot-dropped", name);
        } else if (!areas.isIn(winner, ValleyModule.AREA) || areas.zone(winner) == PveArea.ZoneType.SAFE) {
            civ.messages().send(winner, "valley.boss.loot-lost");
            broadcast2("valley.boss.loot-lost-all", name);
        } else {
            for (ItemStack s : loot) Give.give(winner, s);
            broadcast2("valley.boss.loot-given", name);
        }
    }

    private void broadcast2(String key, String name) {
        Bukkit.getServer().sendMessage(civ.messages().prefix().append(civ.messages().component(key, Messages.arg("player", name))));
    }

    private void pay(UUID id, long cents, double fraction) {
        if (cents <= 0) return;
        Resident r = civ.state().resident(id);
        if (r == null) return;
        r.addBalance(cents);
        civ.state().save(r);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            if (fraction >= 0) {
                civ.messages().send(p, "valley.boss.paid", Messages.money("coins", cents),
                        Messages.arg("percent", String.format(java.util.Locale.ROOT, "%.1f", fraction * 100)));
            } else {
                civ.messages().send(p, "valley.boss.paid-rest", Messages.money("coins", cents));
            }
        }
    }

    // --- info --------------------------------------------------------------------------------------

    void describe(org.bukkit.command.CommandSender sender) {
        if (alive()) {
            AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
            civ.messages().send(sender, "valley.boss.status-alive",
                    Messages.arg("hp", (int) Math.ceil(boss.getHealth())),
                    Messages.arg("max", (int) Math.ceil(max == null ? 0 : max.getValue())),
                    Messages.arg("left", com.civcraft.core.util.Durations.format(Duration.between(Instant.now(), despawnAt))));
        } else {
            Instant next = nextSpawn();
            civ.messages().send(sender, "valley.boss.status-next", Messages.arg("time",
                    next == null ? "—" : com.civcraft.core.util.Durations.format(Duration.between(Instant.now(), next))));
        }
    }

    /** Players whose logout happened inside the boss zone are brought back to the entrance on join. */
    boolean inBossZone(Player p) {
        return areas.isIn(p, ValleyModule.AREA) && areas.zone(p) == PveArea.ZoneType.BOSS;
    }
}
