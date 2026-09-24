package com.civcraft;

import com.civcraft.balance.Balance;
import com.civcraft.clock.GameClock;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.text.Messages;
import com.civcraft.effect.StatService;
import com.civcraft.state.GameState;
import com.civcraft.storage.Database;
import com.civcraft.storage.DocumentStore;
import com.civcraft.storage.SaveQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.event.Listener;

/**
 * The composition root handed to every module: shared services plus lookup of other modules.
 * There is exactly one instance while the plugin is enabled.
 */
public final class CivCraft {

    private static CivCraft instance;

    private final CivCraftPlugin plugin;
    private final Settings settings;
    private final Tasks tasks;
    private final Messages messages;
    private final Database database;
    private final DocumentStore store;
    private final SaveQueue saves;
    private final Balance balance;
    private final GameState state;
    private final GameClock clock;
    private final StatService stats;
    private final List<Module> modules = new ArrayList<>();
    private final com.civcraft.culture.CultureService culture;
    private final com.civcraft.protection.ProtectionService protection = new com.civcraft.protection.ProtectionService();
    private final com.civcraft.template.TemplateService templates;

    CivCraft(CivCraftPlugin plugin, Settings settings, Tasks tasks, Messages messages, Database database,
             DocumentStore store, SaveQueue saves, Balance balance, GameState state, GameClock clock, StatService stats) {
        this.plugin = plugin;
        this.settings = settings;
        this.tasks = tasks;
        this.messages = messages;
        this.database = database;
        this.store = store;
        this.saves = saves;
        this.balance = balance;
        this.state = state;
        this.clock = clock;
        this.stats = stats;
        this.culture = new com.civcraft.culture.CultureService(state, stats, balance);
        this.templates = new com.civcraft.template.TemplateService(plugin);
        instance = this;
    }

    /** Static accessor for code paths that cannot receive the context (item/entity callbacks). */
    public static CivCraft get() {
        if (instance == null) throw new IllegalStateException("CivCraft is not enabled");
        return instance;
    }

    static void clear() {
        instance = null;
    }

    void addModule(Module module) {
        modules.add(module);
    }

    public List<Module> modules() {
        return Collections.unmodifiableList(modules);
    }

    /** Returns the registered module of the given type. */
    public <T extends Module> T module(Class<T> type) {
        for (Module m : modules) {
            if (type.isInstance(m)) return type.cast(m);
        }
        throw new IllegalStateException("Module not registered: " + type.getSimpleName());
    }

    public void listen(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    public CivCraftPlugin plugin() {
        return plugin;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public Settings settings() {
        return settings;
    }

    public Tasks tasks() {
        return tasks;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public DocumentStore store() {
        return store;
    }

    public SaveQueue saves() {
        return saves;
    }

    public Balance balance() {
        return balance;
    }

    public GameState state() {
        return state;
    }

    public GameClock clock() {
        return clock;
    }

    public StatService stats() {
        return stats;
    }

    public com.civcraft.culture.CultureService culture() {
        return culture;
    }

    public com.civcraft.template.TemplateService templates() {
        return templates;
    }

    public com.civcraft.protection.ProtectionService protection() {
        return protection;
    }

    /** Looks up a module by an API interface it implements (e.g. {@code api(ItemApi.class)}). */
    public <T> T api(Class<T> type) {
        for (Module m : modules) {
            if (type.isInstance(m)) return type.cast(m);
        }
        throw new IllegalStateException("No module provides " + type.getSimpleName());
    }

    /** Like {@link #api} but returns null when no module provides the API (optional integrations). */
    public <T> T apiOrNull(Class<T> type) {
        for (Module m : modules) {
            if (type.isInstance(m)) return type.cast(m);
        }
        return null;
    }
}
