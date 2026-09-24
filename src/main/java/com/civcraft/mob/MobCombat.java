package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Resident;
import com.civcraft.pve.Coins;
import com.civcraft.pve.ItemSpec;
import com.civcraft.pve.PveKeys;
import com.civcraft.worldevent.WorldEventApi;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Combat rules and abilities of custom mobs (spec 04 §10.1–10.5, §12). */
final class MobCombat implements Listener {

    private final CivCraft civ;
    private final MobService mobs;
    private final Coins coins;
    /** Set while re-applying armour-ignoring damage so the nested damage event is not processed again. */
    private boolean applyingTrueDamage;

    MobCombat(CivCraft civ, MobService mobs, Coins coins) {
        this.civ = civ;
        this.mobs = mobs;
        this.coins = coins;
    }

    private MobConfig cfg() {
        return mobs.config();
    }

    private boolean protectedPlayer(Entity e) {
        if (!(e instanceof Player p)) return false;
        Resident r = civ.state().resident(p);
        return r != null && r.isPvpProtected();
    }

    private static Player playerAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private static LivingEntity mobAttacker(Entity damager) {
        if (MobService.isCustom(damager) && damager instanceof LivingEntity l) return l;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity l && MobService.isCustom(l)) return l;
        return null;
    }

    /** Equipment tier of an item: the item module's PDC tier, else the vanilla material table. */
    int weaponTier(ItemStack item) {
        if (item == null || item.getType().isAir()) return cfg().defaultWeaponTier;
        if (item.hasItemMeta()) {
            Integer t = item.getItemMeta().getPersistentDataContainer().get(PveKeys.ITEM_TIER, PersistentDataType.INTEGER);
            if (t != null) return t;
        }
        return cfg().weaponTiers.getOrDefault(item.getType(), cfg().defaultWeaponTier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getProjectile() instanceof Projectile proj)) return;
        proj.getPersistentDataContainer().set(PveKeys.ARROW_TIER, PersistentDataType.INTEGER, weaponTier(event.getBow()));
        String bowId = ItemSpec.customId(event.getBow());
        if (bowId != null) proj.getPersistentDataContainer().set(PveKeys.ARROW_BOW, PersistentDataType.STRING, bowId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (applyingTrueDamage) return;
        Entity victim = event.getEntity();
        if (MobService.isCustom(victim) && victim instanceof LivingEntity mob) {
            onMobHurt(event, mob);
            return;
        }
        LivingEntity attacker = mobAttacker(event.getDamager());
        if (attacker != null && victim instanceof Player player) onMobAttack(event, attacker, player);
    }

    private void onMobHurt(EntityDamageByEntityEvent event, LivingEntity mob) {
        MobDef def = mobs.def(mob);
        if (def == null) return;
        Player player = playerAttacker(event.getDamager());
        if (player != null) {
            if (protectedPlayer(player)) {
                event.setCancelled(true);
                civ.messages().actionBar(player, "mobs.newbie-no-fight");
                return;
            }
            if (cfg().weaponTierRule) {
                int tier = event.getDamager() instanceof Projectile proj
                        ? proj.getPersistentDataContainer().getOrDefault(PveKeys.ARROW_TIER, PersistentDataType.INTEGER, cfg().defaultWeaponTier)
                        : weaponTier(player.getInventory().getItemInMainHand());
                if (tier < def.tier().level() - 1) {
                    event.setCancelled(true);
                    civ.messages().actionBar(player, "mobs.weapon-too-weak",
                            Messages.arg("tier", def.tier().level() - 1));
                    return;
                }
            }
            if (player.isInsideVehicle()) player.leaveVehicle();
        }
        if (def.defence() > 0) {
            event.setDamage(Math.max(cfg().minDamageAfterDefence, event.getDamage() - def.defence()));
        }
        if (player == null) return;
        switch (def.type()) {
            case YOBO -> yoboWave(mob, def, player);
            case BEHEMOTH -> {
                mob.getPersistentDataContainer().set(PveKeys.AGGRO_UNTIL, PersistentDataType.LONG,
                        System.currentTimeMillis() + cfg().aggroSeconds * 1000L);
                if (mob instanceof Mob m) m.setTarget(player);
            }
            default -> {
            }
        }
    }

    private void yoboWave(LivingEntity yobo, MobDef def, Player attacker) {
        var pdc = yobo.getPersistentDataContainer();
        int waves = pdc.getOrDefault(PveKeys.YOBO_WAVES, PersistentDataType.INTEGER, 0);
        long last = pdc.getOrDefault(PveKeys.YOBO_LAST_WAVE, PersistentDataType.LONG, 0L);
        long now = System.currentTimeMillis();
        if (waves >= cfg().maxWaves || (waves > 0 && now - last < cfg().waveCooldownMillis)) return;
        pdc.set(PveKeys.YOBO_WAVES, PersistentDataType.INTEGER, waves + 1);
        pdc.set(PveKeys.YOBO_LAST_WAVE, PersistentDataType.LONG, now);
        Location base = yobo.getLocation();
        for (int i = 0; i < cfg().angryCount; i++) {
            double angle = 2 * Math.PI * i / Math.max(1, cfg().angryCount);
            Location at = base.clone().add(Math.cos(angle) * 1.5, 0.2, Math.sin(angle) * 1.5);
            if (!at.getBlock().isPassable()) at = base.clone();
            LivingEntity angry = mobs.spawn(MobType.ANGRY_YOBO, def.tier(), at);
            if (angry instanceof Mob m) m.setTarget(attacker);
        }
    }

    private void onMobAttack(EntityDamageByEntityEvent event, LivingEntity attacker, Player player) {
        if (protectedPlayer(player)) {
            event.setCancelled(true);
            return;
        }
        MobDef def = mobs.def(attacker);
        if (def == null) return;
        if (def.type() == MobType.SAVAGE && ThreadLocalRandom.current().nextDouble(100) < cfg().slowChance) {
            int ticks = (int) Math.round(cfg().slowSeconds[def.tier().level() - 1] * 20);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, cfg().slowAmplifier));
        }
        if (def.ignoreArmor() && event.getDamager() == attacker) {
            double damage = event.getDamage();
            event.setCancelled(true);
            applyingTrueDamage = true;
            try {
                player.damage(damage, DamageSource.builder(DamageType.MAGIC)
                        .withCausingEntity(attacker).withDirectEntity(attacker).build());
            } finally {
                applyingTrueDamage = false;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!MobService.isCustom(event.getEntity())) return;
        if (cfg().immune.contains(event.getCause())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity l && MobService.isCustom(l)) {
            civ.tasks().nextTick(() -> mobs.refreshName(l));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof LivingEntity l && MobService.isCustom(l)) {
            civ.tasks().nextTick(() -> mobs.refreshName(l));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (!cfg().noSunBurn || !MobService.isCustom(event.getEntity())) return;
        if (!(event instanceof EntityCombustByEntityEvent) && !(event instanceof EntityCombustByBlockEvent)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        Entity e = event.getEntity();
        if (!MobService.isCustom(e)) return;
        if (protectedPlayer(event.getTarget())) {
            event.setCancelled(true);
            return;
        }
        if (MobService.type(e) == MobType.BEHEMOTH && event.getReason() != EntityTargetEvent.TargetReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (MobService.isCustom(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        // Silverfish rats would hide in stone; zombies would break doors.
        if (MobService.isCustom(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (MobService.isCustom(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof LivingEntity thrower) || MobService.type(thrower) != MobType.RUFFIAN) return;
        MobTier tier = MobService.tier(thrower);
        double multiplier = 1 + cfg().potionBonusPerTier * (tier == null ? 1 : tier.level());
        for (LivingEntity affected : List.copyOf(event.getAffectedEntities())) {
            if (protectedPlayer(affected) || MobService.isCustom(affected)) {
                event.setIntensity(affected, 0);
            } else {
                event.setIntensity(affected, event.getIntensity(affected) * multiplier);
            }
        }
        Location center = potion.getLocation();
        for (Player p : center.getNearbyPlayers(cfg().splashRadius)) {
            if (event.getAffectedEntities().contains(p) || protectedPlayer(p)) continue;
            double distance = p.getLocation().distance(center);
            double intensity = Math.max(0, 1 - distance / cfg().splashRadius) * multiplier;
            if (intensity > 0) event.setIntensity(p, intensity);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        MobDef def = mobs.def(dead);
        if (def == null) return;
        List<ItemStack> drops = event.getDrops();
        drops.removeIf(stack -> stack == null || !def.keepVanilla().contains(stack.getType()));
        Player killer = dead.getKiller();
        if (killer == null && cfg().requirePlayerKill) {
            event.setDroppedExp(0);
            return;
        }
        event.setDroppedExp(def.xp());
        int looting = killer == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
        for (ItemSpec spec : cfg().commonDrops) {
            if (!spec.roll()) continue;
            int amount = spec.rollAmount() + (looting > 0 ? ThreadLocalRandom.current().nextInt(looting + 1) : 0);
            add(drops, spec.build(amount));
        }
        if (def.tier() == MobTier.LESSER && def.type() != MobType.RAT) {
            for (ItemSpec spec : cfg().lesserDrops) if (spec.roll()) add(drops, spec.build());
        }
        boolean angry = def.type() == MobType.ANGRY_YOBO;
        double mercury = eventValue("mob." + def.type().id() + ".mercury_multiplier");
        double mercuryFactor = mercury > 0 ? mercury : 1;
        for (ItemSpec spec : dropsFor(def)) {
            if (angry && spec.flag("no-angry")) continue;
            if (spec.roll(spec.flag("mercury") ? mercuryFactor : 1)) add(drops, spec.build());
        }
        long cents = Coins.roll(def.coinsMin(), def.coinsMax());
        if (cents > 0) coins.drop(dead.getLocation(), cents, killer == null ? null : killer.getUniqueId());
    }

    /** Angry Yobo share the drop table of the big Yobo of the same tier unless they have their own. */
    private List<ItemSpec> dropsFor(MobDef def) {
        if (def.type() == MobType.ANGRY_YOBO && def.drops().isEmpty()) {
            MobDef parent = cfg().def(MobType.YOBO, def.tier());
            return parent == null ? List.of() : parent.drops();
        }
        return def.drops();
    }

    private double eventValue(String key) {
        WorldEventApi events = civ.apiOrNull(WorldEventApi.class);
        return events == null ? 0 : events.global(key);
    }

    private static void add(List<ItemStack> drops, ItemStack stack) {
        if (stack != null && stack.getAmount() > 0) drops.add(stack);
    }
}
