package com.civcraft;

import com.civcraft.storage.Database;
import java.io.File;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Typed view of config.yml. */
public record Settings(String language, Database.Settings database, int autosaveSeconds, String mainWorld,
                       List<String> extraWorlds, ZoneId zone, int dailyTickHour, String defaultTheme, boolean debug) {

    public static Settings from(FileConfiguration c, File dataFolder) {
        String type = c.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        Database.Settings db = new Database.Settings(
                type.equals("mysql") || type.equals("mariadb") ? Database.Dialect.MYSQL : Database.Dialect.SQLITE,
                new File(dataFolder, c.getString("storage.sqlite-file", "civcraft.db")),
                c.getString("storage.mysql.host", "localhost"),
                c.getInt("storage.mysql.port", 3306),
                c.getString("storage.mysql.database", "civcraft"),
                c.getString("storage.mysql.user", "civcraft"),
                c.getString("storage.mysql.password", ""),
                c.getString("storage.table-prefix", "civ_"),
                c.getInt("storage.mysql.pool-size", 6));
        return new Settings(
                c.getString("language", "ru_RU"),
                db,
                Math.max(2, c.getInt("storage.autosave-seconds", 10)),
                c.getString("worlds.main", "world"),
                c.getStringList("worlds.extra"),
                ZoneId.of(c.getString("timezone", "Europe/Moscow")),
                c.getInt("daily-tick-hour", 20),
                c.getString("templates.default-theme", "default"),
                c.getBoolean("debug", false));
    }

    /** Whether CivCraft gameplay (claims, structures) is active in this world. */
    public boolean isGameWorld(World world) {
        return world != null && isGameWorld(world.getName());
    }

    public boolean isGameWorld(String world) {
        return world.equals(mainWorld) || extraWorlds.contains(world);
    }
}
