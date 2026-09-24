package com.civcraft.integration;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.event.CultureChangedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

/**
 * Optional integrations. Each hook class is only touched when its plugin is present, so missing
 * plugins never cause NoClassDefFoundError.
 */
public final class IntegrationModule implements Module, Listener {

    private Object bluemap;
    private Object dynmap;
    private boolean refreshQueued;
    private CivCraft civ;

    @Override
    public String id() {
        return "integration";
    }

    @Override
    public void enable(CivCraft civ) {
        this.civ = civ;
        PluginManager pm = civ.plugin().getServer().getPluginManager();
        var config = civ.plugin().getConfig();
        if (config.getBoolean("integrations.placeholderapi", true) && pm.isPluginEnabled("PlaceholderAPI")) {
            new CivPlaceholders(civ).register();
            civ.logger().info("PlaceholderAPI expansion registered (%civcraft_...%)");
        }
        if (config.getBoolean("integrations.bluemap", true) && pm.getPlugin("BlueMap") != null) {
            bluemap = new BlueMapHook(civ);
            civ.logger().info("BlueMap integration enabled");
        }
        if (config.getBoolean("integrations.dynmap", true) && pm.getPlugin("dynmap") != null) {
            dynmap = new DynmapHook(civ);
            civ.logger().info("Dynmap integration enabled");
        }
        if (bluemap != null || dynmap != null) {
            civ.listen(this);
            civ.tasks().timer(20 * 60, 20 * 60 * 5, this::refresh);
        }
    }

    @EventHandler
    public void onCulture(CultureChangedEvent event) {
        if (refreshQueued) return;
        refreshQueued = true;
        civ.tasks().later(20 * 5, () -> {
            refreshQueued = false;
            refresh();
        });
    }

    private void refresh() {
        if (bluemap != null) ((BlueMapHook) bluemap).refresh();
        if (dynmap != null) ((DynmapHook) dynmap).refresh();
    }

    @Override
    public void disable(CivCraft civ) {
        if (bluemap != null) ((BlueMapHook) bluemap).disable();
        if (dynmap != null) ((DynmapHook) dynmap).disable();
    }
}
