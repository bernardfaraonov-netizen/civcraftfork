package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.pve.PveKeys;
import java.time.Duration;
import java.time.Instant;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;

/**
 * Removes dropped items and hostile mobs every N minutes with warnings (spec 01 §1, 04 §10.1).
 * Player-named or otherwise persistent monsters, the world boss and tagged event entities are kept.
 */
final class ClearLag {

    private final CivCraft civ;
    private final MobService mobs;
    private Instant next;

    ClearLag(CivCraft civ, MobService mobs) {
        this.civ = civ;
        this.mobs = mobs;
    }

    void start() {
        if (!mobs.config().clearEnabled) return;
        next = Instant.now().plus(Duration.ofMinutes(mobs.config().clearIntervalMinutes));
        civ.tasks().timer(20, 20, this::tick);
    }

    Instant next() {
        return next;
    }

    private void tick() {
        if (next == null) return;
        long left = Duration.between(Instant.now(), next).toSeconds();
        if (left <= 0) {
            next = Instant.now().plus(Duration.ofMinutes(mobs.config().clearIntervalMinutes));
            run();
            return;
        }
        if (mobs.config().clearWarnings.contains((int) left)) {
            Bukkit.getServer().sendMessage(civ.messages().prefix()
                    .append(civ.messages().component("mobs.clearlag.warning", Messages.arg("seconds", left))));
        }
    }

    /** Runs a sweep now; returns the number of removed entities. */
    int run() {
        MobConfig c = mobs.config();
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (shouldRemove(e, c)) {
                    e.remove();
                    removed++;
                }
            }
        }
        Bukkit.getServer().sendMessage(civ.messages().prefix()
                .append(civ.messages().component("mobs.clearlag.done", Messages.arg("count", removed))));
        new ClearLagEvent(removed).call();
        return removed;
    }

    private boolean shouldRemove(Entity e, MobConfig c) {
        var pdc = e.getPersistentDataContainer();
        if (pdc.has(PveKeys.KEEP) || pdc.has(PveKeys.BOSS) || pdc.has(PveKeys.PLAGUE_TOWN)) return false;
        if (e instanceof Item item) {
            return c.clearItems && !item.getItemStack().getType().isAir();
        }
        if (MobService.isCustom(e)) return true;
        if (c.clearVanillaMonsters && e instanceof Monster m) {
            return m.customName() == null && m.getRemoveWhenFarAway() && !m.isLeashed() && m.getVehicle() == null;
        }
        return false;
    }
}
