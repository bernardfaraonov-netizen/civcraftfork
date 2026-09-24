package com.civcraft.economy;

import com.civcraft.CivCraft;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.storage.Stored;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Surface biome of every chunk that matters (culture chunks, surveys), persisted per 32×32 region.
 * A chunk's biome is the biome at the centre of the chunk at the {@code WORLD_SURFACE} height (spec
 * §9.2). Unknown chunks are resolved from loaded chunks or by loading them asynchronously at a
 * bounded rate — never by blocking the main thread (legacy loaded chunks synchronously on every
 * culture recalculation, audit A-M23).
 */
public final class BiomeCache implements Listener {

    public static final String COLLECTION = "chunk_biomes";

    /** One 32×32 chunk region of cached biomes. */
    public static final class Region implements Stored {
        private String id;
        private Map<String, String> biomes = new HashMap<>();

        private Region() {
        }

        Region(String id) {
            this.id = id;
        }

        @Override
        public String storageId() {
            return id;
        }
    }

    private final CivCraft civ;
    private final Map<String, Region> regions = new HashMap<>();
    private final Deque<ChunkKey> queue = new ArrayDeque<>();
    private final Set<ChunkKey> queued = new HashSet<>();
    private int inFlight;
    private boolean changed;
    private Runnable onChange = () -> { };

    public BiomeCache(CivCraft civ) {
        this.civ = civ;
    }

    public void load() {
        civ.store().createCollection(COLLECTION);
        for (Region r : civ.store().loadAll(COLLECTION, Region.class)) regions.put(r.id, r);
    }

    /** Called (at most once per tick batch) after new biomes were resolved. */
    public void onChange(Runnable r) {
        this.onChange = r;
    }

    private static String regionId(ChunkKey c) {
        return c.world() + ":" + (c.x() >> 5) + "," + (c.z() >> 5);
    }

    private static String local(ChunkKey c) {
        return c.x() + "," + c.z();
    }

    /** Cached biome key or null when not yet known (the chunk is queued for resolution). */
    public String biome(ChunkKey chunk) {
        Region r = regions.get(regionId(chunk));
        String b = r == null ? null : r.biomes.get(local(chunk));
        if (b == null) request(chunk);
        return b;
    }

    public boolean known(ChunkKey chunk) {
        Region r = regions.get(regionId(chunk));
        return r != null && r.biomes.containsKey(local(chunk));
    }

    public void request(ChunkKey chunk) {
        if (queued.add(chunk)) queue.add(chunk);
    }

    private void put(ChunkKey chunk, String biome) {
        Region r = regions.computeIfAbsent(regionId(chunk), Region::new);
        if (biome.equals(r.biomes.put(local(chunk), biome))) return;
        civ.saves().save(COLLECTION, r);
        changed = true;
    }

    private void resolve(Chunk chunk) {
        World w = chunk.getWorld();
        int x = (chunk.getX() << 4) + 8;
        int z = (chunk.getZ() << 4) + 8;
        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        Biome biome = w.getBiome(x, y, z);
        put(ChunkKey.of(chunk), biome.getKey().toString());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!civ.settings().isGameWorld(e.getWorld())) return;
        ChunkKey key = ChunkKey.of(e.getChunk());
        if (!known(key)) resolve(e.getChunk());
    }

    /** Resolves queued chunks; run every second with a small budget of async loads. */
    public void tick() {
        int budget = civ.balance().getInt("economy", "biome-scan-per-second", 16);
        while (inFlight < budget && !queue.isEmpty()) {
            ChunkKey key = queue.poll();
            queued.remove(key);
            if (known(key)) continue;
            World w = org.bukkit.Bukkit.getWorld(key.world());
            if (w == null) continue;
            if (w.isChunkLoaded(key.x(), key.z())) {
                resolve(w.getChunkAt(key.x(), key.z()));
                continue;
            }
            inFlight++;
            w.getChunkAtAsync(key.x(), key.z(), true).whenComplete((chunk, error) -> civ.tasks().sync(() -> {
                inFlight--;
                if (chunk != null) resolve(chunk);
            }));
        }
        if (changed) {
            changed = false;
            onChange.run();
        }
    }

    public int pending() {
        return queue.size() + inFlight;
    }
}
