package com.civcraft.coremod;

import com.civcraft.CivCraft;
import com.civcraft.core.util.Money;
import com.civcraft.model.Resident;
import java.time.Instant;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Projectile;

/** Creates residents on first join, keeps names/last-seen fresh and runs newbie PvP protection. */
public final class ResidentListener implements Listener {

    private final CivCraft civ;

    public ResidentListener(CivCraft civ) {
        this.civ = civ;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Resident resident = civ.state().resident(player);
        if (resident == null) {
            resident = new Resident(player.getUniqueId(), player.getName(),
                    civ.balance().coins("core", "resident.starting-balance", 200),
                    60L * civ.balance().getInt("core", "resident.pvp-protection-minutes", 30));
            civ.state().addResident(resident);
            civ.messages().send(player, "resident.welcome");
        } else if (!resident.name().equals(player.getName())) {
            civ.state().renameResident(resident, player.getName());
        }
        resident.lastSeen(Instant.now());
        civ.state().save(resident);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Resident resident = civ.state().resident(event.getPlayer());
        if (resident != null) {
            resident.lastSeen(Instant.now());
            civ.state().save(resident);
        }
    }

    /** Called every second by the clock: count down PvP protection, drop it far from spawn. */
    public void tick() {
        int distance = civ.balance().getInt("core", "resident.pvp-protection-distance", 2000);
        long distanceSq = (long) distance * distance;
        for (Player player : civ.plugin().getServer().getOnlinePlayers()) {
            Resident r = civ.state().resident(player);
            if (r == null || !r.isPvpProtected()) continue;
            r.pvpProtectionSeconds(r.pvpProtectionSeconds() - 1);
            var spawn = player.getWorld().getSpawnLocation();
            if (player.getLocation().distanceSquared(spawn) > distanceSq) r.pvpProtectionSeconds(0);
            if (!r.isPvpProtected()) civ.messages().send(player, "resident.pvp-protection-ended");
            civ.state().save(r);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker == null || attacker.equals(victim)) return;
        Resident a = civ.state().resident(attacker);
        Resident v = civ.state().resident(victim);
        if (a != null && a.isPvpProtected()) {
            event.setCancelled(true);
            civ.messages().actionBar(attacker, "resident.pvp-protected-self");
        } else if (v != null && v.isPvpProtected()) {
            event.setCancelled(true);
            civ.messages().actionBar(attacker, "resident.pvp-protected-target");
        }
    }

    static long startingBalance(CivCraft civ) {
        return Money.ofCoins(civ.balance().getDouble("core", "resident.starting-balance", 200));
    }
}
