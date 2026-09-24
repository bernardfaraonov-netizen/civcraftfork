package com.civcraft.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Collects {@code /civadmin} (aliases {@code /ca}, {@code /ad}) sub-commands from modules and registers
 * the root command once with permission {@code civcraft.admin}. Modules call {@link #add} from
 * {@code enable()}; the core module calls {@link #register} before modules are enabled.
 */
public final class AdminRegistry {

    public static final String PERMISSION = "civcraft.admin";
    private static final List<LiteralArgumentBuilder<CommandSourceStack>> CHILDREN = new ArrayList<>();
    private static boolean registered;

    private AdminRegistry() {
    }

    public static synchronized void add(LiteralArgumentBuilder<CommandSourceStack> child) {
        CHILDREN.add(child);
        if (!registered) register(com.civcraft.CivCraft.get().plugin());
    }

    /** Hooks the command registration; safe to call more than once. */
    public static synchronized void register(JavaPlugin plugin) {
        if (registered) return;
        registered = true;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("civadmin")
                    .requires(source -> source.getSender().hasPermission(PERMISSION));
            synchronized (AdminRegistry.class) {
                for (LiteralArgumentBuilder<CommandSourceStack> child : CHILDREN) root.then(child);
            }
            event.registrar().register(root.build(), "CivCraft administration", List.of("ca", "ad"));
        });
    }
}
