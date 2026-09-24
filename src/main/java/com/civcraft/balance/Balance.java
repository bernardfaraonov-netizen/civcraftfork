package com.civcraft.balance;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Loads balance files ({@code balance/<name>.yml}). The bundled file provides defaults; a copy in the
 * plugin folder overrides individual keys, so updates add new keys without clobbering edits.
 */
public final class Balance {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, YamlConfiguration> files = new HashMap<>();

    public Balance(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public YamlConfiguration file(String name) {
        return files.computeIfAbsent(name, this::read);
    }

    public void reload() {
        files.clear();
    }

    private YamlConfiguration read(String name) {
        String path = "balance/" + name + ".yml";
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                logger.warning("No bundled balance file " + path);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
        File override = new File(plugin.getDataFolder(), path);
        if (!override.isFile()) {
            if (plugin.getResource(path) != null) plugin.saveResource(path, false);
            return defaults;
        }
        YamlConfiguration custom = YamlConfiguration.loadConfiguration(override);
        custom.setDefaults(defaults);
        return custom;
    }

    public ConfigurationSection section(String file, String path) {
        ConfigurationSection s = file(file).getConfigurationSection(path);
        return s != null ? s : new YamlConfiguration();
    }

    public double getDouble(String file, String path, double def) {
        return file(file).getDouble(path, def);
    }

    public int getInt(String file, String path, int def) {
        return file(file).getInt(path, def);
    }

    public long coins(String file, String path, double def) {
        return com.civcraft.core.util.Money.ofCoins(file(file).getDouble(path, def));
    }
}
