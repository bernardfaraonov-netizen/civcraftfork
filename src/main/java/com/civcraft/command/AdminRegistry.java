package com.civcraft.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Collects {@code /civadmin} sub-commands from modules and registers the root command once (permission
 * {@code civcraft.admin}). Modules call {@link #add} from {@code enable()}; the core module calls {@link #register}.
 */
public final class AdminRegistry {

    public static final String PERMISSION = "civcraft.admin";
    private static final List<LiteralArgumentBuilder<CommandSourceStack>> CHILDREN = new ArrayList<>();

    private AdminRegistry() {
    }

    public static void add(LiteralArgumentBuilder<CommandSourceStack> child) {
        CHILDREN.add(child);
    }

    /** Registers {@code /civadmin} (alias {@code /ad}) with every added sub-command when commands are (re)built. */
    public static void register(JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("civadmin")
                    .requires(source -> source.getSender().hasPermission(PERMISSION));
            for (LiteralArgumentBuilder<CommandSourceStack> child : CHILDREN) root.then(child);
            event.registrar().register(root.build(), "CivCraft administration", List.of("ad"));
        });
    }
}
