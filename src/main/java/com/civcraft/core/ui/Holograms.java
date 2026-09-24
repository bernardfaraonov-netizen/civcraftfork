package com.civcraft.core.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Text holograms backed by non-persistent {@link TextDisplay} entities. They are recreated by their
 * owners after restarts, so nothing is ever left floating in the world if the plugin is removed.
 */
public final class Holograms {

    private static NamespacedKey key;
    private static final Map<String, UUID> active = new HashMap<>();

    private Holograms() {
    }

    public static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "hologram");
        // Remove leftovers from a crash (persistent=false should prevent them, but be safe).
        for (var world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(key)) display.remove();
            }
        }
    }

    /** Creates or updates the hologram with the given id. */
    public static void show(String id, Location location, Component text) {
        TextDisplay display = find(id);
        if (display == null || !display.isValid() || !display.getWorld().equals(location.getWorld())) {
            if (display != null) display.remove();
            if (!location.isChunkLoaded()) return;
            display = location.getWorld().spawn(location, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
            });
            active.put(id, display.getUniqueId());
        } else if (display.getLocation().distanceSquared(location) > 0.01) {
            display.teleport(location);
        }
        display.text(text);
    }

    public static void remove(String id) {
        TextDisplay display = find(id);
        if (display != null) display.remove();
        active.remove(id);
    }

    public static void removeAll() {
        for (String id : java.util.List.copyOf(active.keySet())) remove(id);
    }

    private static TextDisplay find(String id) {
        UUID uuid = active.get(id);
        if (uuid == null) return null;
        Entity entity = Bukkit.getEntity(uuid);
        return entity instanceof TextDisplay td ? td : null;
    }
}
