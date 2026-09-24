package com.civcraft.pve;

import com.civcraft.core.util.BlockPos;
import com.civcraft.storage.Stored;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A special PvE world (Air valley, dungeon): spawn point, zones and named marker lists. Configured
 * in game by admins ({@code /civadmin area ...}) because the maps are custom builds.
 */
public final class PveArea implements Stored {

    public enum ZoneType { SAFE, PVP, BOSS }

    public static final class Zone {
        private String name;
        private ZoneType type;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;

        private Zone() {
        }

        public Zone(String name, ZoneType type, BlockPos a, BlockPos b) {
            this.name = name;
            this.type = type;
            this.minX = Math.min(a.x(), b.x());
            this.minY = Math.min(a.y(), b.y());
            this.minZ = Math.min(a.z(), b.z());
            this.maxX = Math.max(a.x(), b.x());
            this.maxY = Math.max(a.y(), b.y());
            this.maxZ = Math.max(a.z(), b.z());
        }

        public String name() {
            return name;
        }

        public ZoneType type() {
            return type;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public String bounds() {
            return minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ;
        }
    }

    private String id;
    private String world;
    private BlockPos spawn;
    private float spawnYaw;
    private List<Zone> zones = new ArrayList<>();
    private Map<String, List<BlockPos>> markers = new LinkedHashMap<>();

    private PveArea() {
    }

    public PveArea(String id) {
        this.id = id;
    }

    @Override
    public String storageId() {
        return id;
    }

    public String id() {
        return id;
    }

    public String worldName() {
        return world;
    }

    public void worldName(String world) {
        this.world = world;
    }

    public World world() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    public boolean isIn(World w) {
        return w != null && w.getName().equals(world);
    }

    public BlockPos spawn() {
        return spawn;
    }

    public void spawn(Location location) {
        this.world = location.getWorld().getName();
        this.spawn = BlockPos.of(location);
        this.spawnYaw = location.getYaw();
    }

    public Location spawnLocation() {
        World w = world();
        if (w == null || spawn == null) return null;
        Location l = new Location(w, spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);
        l.setYaw(spawnYaw);
        return l;
    }

    public boolean configured() {
        return world() != null && spawn != null;
    }

    public List<Zone> zones() {
        return zones;
    }

    public Zone zone(String name) {
        for (Zone z : zones) if (z.name().equalsIgnoreCase(name)) return z;
        return null;
    }

    /** Zone at the position; positions outside every zone get {@code fallback}. */
    public ZoneType zoneAt(Location l, ZoneType fallback) {
        if (!isIn(l.getWorld())) return null;
        // Later zones take precedence so admins can carve safe rooms out of a large PvP zone.
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (z.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ())) return z.type();
        }
        return fallback;
    }

    public List<BlockPos> markers(String kind) {
        return markers.getOrDefault(kind, List.of());
    }

    public void addMarker(String kind, BlockPos pos) {
        markers.computeIfAbsent(kind, k -> new ArrayList<>()).add(pos);
    }

    public boolean removeMarker(String kind, BlockPos pos) {
        List<BlockPos> list = markers.get(kind);
        return list != null && list.remove(pos);
    }

    public void clearMarkers(String kind) {
        markers.remove(kind);
    }

    public Map<String, List<BlockPos>> allMarkers() {
        return markers;
    }
}
