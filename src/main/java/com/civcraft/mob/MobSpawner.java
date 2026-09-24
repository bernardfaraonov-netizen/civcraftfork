package com.civcraft.mob;

import com.civcraft.CivCraft;
import com.civcraft.core.util.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Custom mob spawning (spec 04 §10.1). A budgeted round-robin over the online players (no unbounded
 * queue — legacy bug D.42): each run looks at the next few players and tops up the custom mob
 * population around them. Only already loaded chunks are touched, never town or camp land. Natural
 * vanilla monster spawns in mapped biomes are partly replaced by custom mobs.
 */
final class MobSpawner implements Listener {

    private final CivCraft civ;
    private final MobService mobs;
    private int cursor;

    MobSpawner(CivCraft civ, MobService mobs) {
        this.civ = civ;
        this.mobs = mobs;
    }

    private MobConfig cfg() {
        return mobs.config();
    }

    void run() {
        MobConfig c = cfg();
        if (!c.spawnEnabled || c.playerCap <= 0) return;
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        players.sort(Comparator.comparing(Player::getUniqueId));
        int count = Math.min(players.size(), c.spawnPlayersPerRun);
        for (int i = 0; i < count; i++) {
            cursor = (cursor + 1) % players.size();
            spawnAround(players.get(cursor));
        }
    }

    private void spawnAround(Player player) {
        MobConfig c = cfg();
        World world = player.getWorld();
        if (!c.spawnWorlds.contains(world.getName()) || player.getGameMode() == GameMode.SPECTATOR) return;
        Location center = player.getLocation();
        int nearby = countCustom(center, c.playerRadius);
        if (nearby >= c.playerCap) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < c.attempts; attempt++) {
            double angle = rnd.nextDouble(Math.PI * 2);
            double dist = rnd.nextDouble(c.radiusMin, c.radiusMax);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            Location spot = groundAt(world, x, z);
            if (spot == null) continue;
            List<MobConfig.PoolEntry> pool = c.pool(spot.getBlock().getBiome());
            if (pool.isEmpty()) continue;
            if (countCustom(spot, c.localRadius) >= c.localCap) continue;
            MobConfig.PoolEntry pick = pool.get(rnd.nextInt(pool.size()));
            int group = Math.min(c.groupMax, c.playerCap - nearby);
            group = Math.max(1, Math.min(group, rnd.nextInt(c.groupMin, c.groupMax + 1)));
            for (int i = 0; i < group; i++) {
                Location at = spot.clone().add(rnd.nextDouble(-1.5, 1.5), 0, rnd.nextDouble(-1.5, 1.5));
                if (!at.getBlock().isPassable()) at = spot;
                mobs.spawn(pick.type(), pick.tier(), at);
            }
            return;
        }
    }

    /** A spawnable surface position in a loaded, unsettled chunk, or null. Never loads chunks. */
    private Location groundAt(World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        MobConfig c = cfg();
        Location worldSpawn = world.getSpawnLocation();
        double dx = worldSpawn.getX() - x;
        double dz = worldSpawn.getZ() - z;
        if (dx * dx + dz * dz < (double) c.spawnSafeRadius * c.spawnSafeRadius) return null;
        if (civ.state().claim(new ChunkKey(world.getName(), x >> 4, z >> 4)) != null) return null;
        int y = world.getHighestBlockYAt(x, z);
        Block ground = world.getBlockAt(x, y, z);
        if (!ground.getType().isSolid() || ground.isLiquid()) return null;
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return null;
        Location l = new Location(world, x + 0.5, y + 1, z + 0.5);
        if (mobs.nearCamp(l)) return null;
        return l;
    }

    private int countCustom(Location at, double radius) {
        return at.getNearbyLivingEntities(radius, e -> MobService.isCustom(e) && MobService.type(e) != MobType.RAT).size();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(event.getEntity() instanceof Monster)) return;
        MobConfig c = cfg();
        Location l = event.getLocation();
        if (!c.spawnWorlds.contains(l.getWorld().getName())) return;
        List<MobConfig.PoolEntry> pool = c.pool(l.getBlock().getBiome());
        if (pool.isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean settled = civ.state().claim(ChunkKey.of(l)) != null || mobs.nearCamp(l);
        if (!settled && rnd.nextDouble() < c.naturalReplaceChance && countCustom(l, c.localRadius) < c.localCap
                && countCustom(l, c.playerRadius) < c.playerCap * 2) {
            event.setCancelled(true);
            MobConfig.PoolEntry pick = pool.get(rnd.nextInt(pool.size()));
            civ.tasks().nextTick(() -> mobs.spawn(pick.type(), pick.tier(), l));
        } else if (c.cancelVanillaMonsters) {
            event.setCancelled(true);
        }
    }
}
