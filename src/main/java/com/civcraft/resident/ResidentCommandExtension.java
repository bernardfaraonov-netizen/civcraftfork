package com.civcraft.resident;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Lets other modules add subcommands to {@code /resident} ({@code /res tech}, {@code /res hud}...)
 * without registering a conflicting root command. Any module implementing it is asked when the
 * command tree is built.
 */
public interface ResidentCommandExtension {

    void extendResident(LiteralArgumentBuilder<CommandSourceStack> root);
}
