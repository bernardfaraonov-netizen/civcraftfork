package com.civcraft.command;

import com.civcraft.CivCraft;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects admin sub-commands from every module and registers them under {@code /civadmin}
 * (permission {@code civcraft.admin}). Modules call {@link #add} from {@code enable()}.
 */
public final class AdminRegistry {

    public static final String PERMISSION = "civcraft.admin";

    private static final List<LiteralArgumentBuilder<CommandSourceStack>> SUBCOMMANDS = new ArrayList<>();
    private static boolean hooked;

    private AdminRegistry() {
    }

    public static synchronized void add(LiteralArgumentBuilder<CommandSourceStack> sub) {
        SUBCOMMANDS.add(sub);
        if (hooked) return;
        hooked = true;
        CivCraft.get().plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("civadmin")
                    .requires(Cmd.perm(PERMISSION));
            synchronized (AdminRegistry.class) {
                for (LiteralArgumentBuilder<CommandSourceStack> s : SUBCOMMANDS) root.then(s);
            }
            event.registrar().register(root.build(), "CivCraft administration", List.of("ca"));
        });
    }
}
