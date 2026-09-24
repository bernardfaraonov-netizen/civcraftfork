package com.civcraft;

import com.civcraft.balance.Balance;
import com.civcraft.clock.GameClock;
import com.civcraft.command.Cmd;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.text.Messages;
import com.civcraft.effect.StatService;
import com.civcraft.gui.MenuListener;
import com.civcraft.state.GameState;
import com.civcraft.storage.Database;
import com.civcraft.storage.DocumentStore;
import com.civcraft.storage.SaveQueue;
import java.util.List;
import java.util.ListIterator;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivCraftPlugin extends JavaPlugin {

    private CivCraft civ;
    private Database database;

    /** All gameplay modules in dependency order. */
    private static List<Module> createModules() {
        return List.of(
                new com.civcraft.coremod.CoreModule(),
                // PvE (mobs, valley & world boss, dungeon, world/town events, ruins, fishing, kits)
                new com.civcraft.pve.PveModule(),
                new com.civcraft.mob.MobModule(),
                new com.civcraft.dungeon.DungeonModule()
        );
    }

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        saveDefaultConfig();
        Settings settings = Settings.from(getConfig(), getDataFolder());
        Cmd.setLogger(getLogger());
        Tasks tasks = new Tasks(this);
        Messages messages = Messages.load(this, settings.language());
        try {
            database = new Database(settings.database(), getLogger());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Cannot connect to the database; disabling CivCraft", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DocumentStore store = new DocumentStore(database, getLogger());
        SaveQueue saves = new SaveQueue(store);
        GameState state = new GameState(saves);
        state.load(store, getLogger());
        GameClock clock = new GameClock(tasks, getLogger(), settings.zone(), settings.dailyTickHour(), saves);
        clock.load(store);
        StatService stats = new StatService(state, getLogger());
        civ = new CivCraft(this, settings, tasks, messages, database, store, saves, new Balance(this), state, clock, stats);

        for (Module module : createModules()) civ.addModule(module);
        for (Module module : civ.modules()) {
            try {
                module.load(civ);
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Module " + module.id() + " failed to load; disabling CivCraft", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        for (Module module : civ.modules()) module.enable(civ);

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        tasks.timer(20L * settings.autosaveSeconds(), 20L * settings.autosaveSeconds(), saves::flush);
        clock.start();
        getLogger().info("CivCraft enabled in " + (System.currentTimeMillis() - start) + " ms with "
                + civ.modules().size() + " modules");
    }

    @Override
    public void onDisable() {
        if (civ != null) {
            List<Module> modules = civ.modules();
            for (ListIterator<Module> it = modules.listIterator(modules.size()); it.hasPrevious(); ) {
                Module module = it.previous();
                try {
                    module.disable(civ);
                } catch (RuntimeException e) {
                    getLogger().log(Level.SEVERE, "Module " + module.id() + " failed to disable cleanly", e);
                }
            }
            try {
                civ.saves().flush().join();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Final save failed", e);
            }
        }
        if (database != null) database.close();
        CivCraft.clear();
        civ = null;
    }

    public CivCraft civ() {
        return civ;
    }
}
