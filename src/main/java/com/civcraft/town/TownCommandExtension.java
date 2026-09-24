package com.civcraft.town;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Lets other modules add subcommands to {@code /town} ({@code /t repair}, {@code /t barracks},
 * {@code /t quarry}...) without registering a conflicting root command.
 */
public interface TownCommandExtension {

    void extendTown(LiteralArgumentBuilder<CommandSourceStack> root);
}
