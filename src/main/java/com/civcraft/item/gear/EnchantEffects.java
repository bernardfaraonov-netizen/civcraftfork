package com.civcraft.item.gear;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemRules;
import com.civcraft.model.Resident;
import com.civcraft.protection.Action;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Effects of the wonder enchantments (spec 04 §7.2): lightning strike (World University), critical
 * attack and water walking (Angkor Wat), roots (Tree of Life), punchout (Neuschwanstein; rolled by the
 * structure damage code). "Unpleasant" effects never hit friends. Ruin recipes (levitation, blindness,
 * Prince's Knife...) and ruin weapons are handled by the PvE module; «Небесная кара» is mirrored to
 * {@code civcraft:heavenly_punishment} for its world boss code.
 */
public final class EnchantEffects implements Listener {

    private final CivCraft civ;
    private final ItemRules rules;
    private final Realms realms;
    private final Map<UUID, Long> rootsTargetCooldown = new HashMap<>();
    private final Map<UUID, Long> notifyCooldown = new HashMap<>();
    /** victim → (attacker, time) of the last lightning strike, to detect kills by lightning. */
    private final Map<UUID, LightningHit> lastLightning = new HashMap<>();
    /** Owners whose next hit strikes lightning for sure (after a kill by lightning, reset on the owner's death). */
    private final Set<UUID> charged = new HashSet<>();
    /** Water turned into frosted ice by water walking → time to melt back. */
    private final Map<Block, Long> frozen = new HashMap<>();
    private BukkitTask meltTask;

    private record LightningHit(UUID attacker, long at) {
    }

    public EnchantEffects(CivCraft civ, ItemRules rules, Realms realms) {
        this.civ = civ;
        this.rules = rules;
        this.realms = realms;
    }

    public void start() {
        meltTask = civ.tasks().timer(5L, 5L, this::melt);
    }

    public void stop() {
        if (meltTask != null) meltTask.cancel();
        for (Block block : frozen.keySet()) {
            if (block.getType() == Material.FROSTED_ICE) block.setType(Material.WATER);
        }
        frozen.clear();
    }

    // ------------------------------------------------------------------------------------ helpers

    public boolean friends(Player a, LivingEntity b) {
        if (!(b instanceof Player pb)) return false;
        Resident ra = civ.state().resident(a);
        Resident rb = civ.state().resident(pb);
        return (ra != null && ra.friends().contains(pb.getUniqueId()))
                || (rb != null && rb.friends().contains(a.getUniqueId()));
    }

    private static boolean roll(double chance) {
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private boolean muted(Player player) {
        Resident r = civ.state().resident(player);
        return r != null && r.setting(rules.muteSetting);
    }

    /** Punchout (Neuschwanstein): 50% chance of bonus damage to a structure or control block. */
    public boolean rollPunchout(ItemStack tool) {
        return ItemData.customEnchant(tool, "punchout") > 0 && roll(rules.punchoutChance);
    }

    // ------------------------------------------------------------------------------------ on hit

    /**
     * Applies on-hit effects and returns the new damage.
     *
     * @param enchants custom enchantments of the weapon (for arrows: copied at shot time)
     * @param ranged   the hit is an arrow
     */
    public double onHit(Player attacker, LivingEntity victim, Map<String, Integer> enchants, boolean ranged,
                        double damage) {
        if (enchants.isEmpty() || friends(attacker, victim)) return damage;
        if (enchants.getOrDefault("critical", 0) > 0 && roll(rules.criticalChance)) {
            damage += rules.criticalDamage;
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 12);
        }
        if (enchants.getOrDefault("lightning", 0) > 0) {
            boolean sure = charged.remove(attacker.getUniqueId());
            if (sure || roll(rules.lightningChance)) {
                damage += rules.lightningDamage;
                victim.getWorld().strikeLightningEffect(victim.getLocation());
                lastLightning.put(victim.getUniqueId(), new LightningHit(attacker.getUniqueId(), now()));
            }
        }
        if (!ranged && enchants.getOrDefault("roots", 0) > 0 && !realms.inValley(attacker)) roots(attacker, victim);
        return damage;
    }

    private static int ticks(double seconds) {
        return (int) Math.max(1, Math.round(seconds * 20));
    }

    private void roots(Player attacker, LivingEntity victim) {
        Long last = rootsTargetCooldown.get(victim.getUniqueId());
        if (last != null && now() - last < (long) (rules.rootsTargetCooldown * 1000)) return;
        if (!roll(rules.rootsChance)) return;
        rootsTargetCooldown.put(victim.getUniqueId(), now());
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks(rules.rootsSeconds), rules.rootsAmplifier));
        notify(attacker, "items.effect.roots-attacker", Messages.arg("target", victim.getName()));
        if (victim instanceof Player pv) notify(pv, "items.effect.roots-victim", Messages.arg("attacker", attacker.getName()));
    }

    private void notify(Player player, String key, TagResolver arg) {
        if (muted(player)) return;
        Long last = notifyCooldown.get(player.getUniqueId());
        if (last != null && now() - last < (long) (rules.rootsNotifyCooldown * 1000)) return;
        notifyCooldown.put(player.getUniqueId(), now());
        civ.messages().send(player, key, arg);
    }

    // ------------------------------------------------------------------------------------ lightning bookkeeping

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LightningHit hit = lastLightning.remove(event.getEntity().getUniqueId());
        if (hit != null && now() - hit.at() < 1000) charged.add(hit.attacker());
        if (event instanceof PlayerDeathEvent pd) charged.remove(pd.getEntity().getUniqueId());
        rootsTargetCooldown.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        notifyCooldown.remove(id);
        lastLightning.remove(id);
    }

    // ------------------------------------------------------------------------------------ water walking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack boots = player.getInventory().getBoots();
        if (ItemData.customEnchant(boots, "water_walking") <= 0 || player.isFlying() || player.isSwimming()
                || realms.inDungeon(player)) {
            return;
        }
        Block below = to.getBlock().getRelative(BlockFace.DOWN);
        long melt = now() + (long) (rules.waterWalkingSeconds * 1000);
        int r = rules.waterWalkingRadius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Block b = below.getRelative(dx, 0, dz);
                if (b.getType() != Material.WATER || !(b.getBlockData() instanceof Levelled l) || l.getLevel() != 0) continue;
                if (!b.getRelative(BlockFace.UP).getType().isAir()) continue;
                // Structure water is protected by the structure guards; never freeze protected blocks.
                if (civ.protection().check(player, Action.PLACE, b, null).denied()) continue;
                b.setType(Material.FROSTED_ICE);
                frozen.put(b, melt);
            }
        }
    }

    private void melt() {
        long t = now();
        for (Iterator<Map.Entry<Block, Long>> it = frozen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Block, Long> e = it.next();
            if (e.getValue() > t) continue;
            Block b = e.getKey();
            if (b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && b.getType() == Material.FROSTED_ICE) {
                b.setType(Material.WATER);
            }
            it.remove();
        }
    }
}
