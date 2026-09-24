package com.civcraft.civ;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Lets other modules add subcommands to {@code /civ} ({@code /civ research}, {@code /civ talent},
 * {@code /civ religion}, {@code /civ trade}, {@code /civ victory}...) without registering a
 * conflicting root command. Any module implementing it is asked when the command tree is built.
 */
public interface CivCommandExtension {

    void extendCiv(LiteralArgumentBuilder<CommandSourceStack> root);
}
