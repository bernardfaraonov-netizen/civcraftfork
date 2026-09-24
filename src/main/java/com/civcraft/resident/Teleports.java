package com.civcraft.resident;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Teleports with a warm-up countdown (spec §6.7: 10 s, reset by moving or taking damage). The
 * destination and the payment are resolved only when the countdown completes, so nothing is charged
 * for a cancelled teleport and the target is re-validated at the last moment.
 */
public final class Teleports implements Listener {

    /** Called when the countdown completes; returns the destination or null to abort (it messages the player). */
    public interface Completion {
        Location complete(Player player);
    }

    private record Pending(BukkitTask task, Location start, Consumer<Player> onCancel) {
    }

    private final CivCraft civ;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public Teleports(CivCraft civ) {
        this.civ = civ;
    }

    public boolean isWarming(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /**
     * Starts a countdown. {@code completion} runs on the main thread when it ends and returns the
     * location to teleport to (null = abort). {@code onCancel} runs if movement or damage interrupt it.
     */
    public void start(Player player, int seconds, Completion completion, Consumer<Player> onCancel) {
        cancel(player, false);
        if (seconds <= 0) {
            finish(player, completion);
            return;
        }
        int[] left = {seconds};
        Messages.get().actionBar(player, "teleport.countdown", Messages.arg("seconds", left[0]));
        BukkitTask task = civ.tasks().timer(20, 20, () -> {
            left[0]--;
            if (!player.isOnline()) {
                cancel(player, false);
                return;
            }
            if (left[0] > 0) {
                Messages.get().actionBar(player, "teleport.countdown", Messages.arg("seconds", left[0]));
                return;
            }
            Pending p = pending.remove(player.getUniqueId());
            if (p != null) p.task.cancel();
            finish(player, completion);
        });
        pending.put(player.getUniqueId(), new Pending(task, player.getLocation(), onCancel));
    }

    private void finish(Player player, Completion completion) {
        Location target = completion.complete(player);
        if (target == null) return;
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public void cancel(Player player, boolean notify) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        p.task.cancel();
        if (notify) {
            Messages.get().send(player, "teleport.cancelled");
            if (p.onCancel != null) p.onCancel.accept(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Pending p = pending.get(e.getPlayer().getUniqueId());
        if (p == null) return;
        Location to = e.getTo();
        if (to.getBlockX() != p.start.getBlockX() || to.getBlockY() != p.start.getBlockY()
                || to.getBlockZ() != p.start.getBlockZ() || !to.getWorld().equals(p.start.getWorld())) {
            cancel(e.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())) {
            cancel(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer(), false);
    }

    /** Convenience for suppliers of locations that may be missing. */
    public static Location orNull(Supplier<Location> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
