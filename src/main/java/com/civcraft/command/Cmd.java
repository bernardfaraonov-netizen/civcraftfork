package com.civcraft.command;

import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Helpers for building Brigadier command trees with uniform error handling. */
public final class Cmd {

    private static Logger logger = Logger.getLogger("CivCraft");

    private Cmd() {
    }

    public static void setLogger(Logger l) {
        logger = l;
    }

    @FunctionalInterface
    public interface Action {
        void run(CommandContext<CommandSourceStack> ctx) throws CivException;
    }

    @FunctionalInterface
    public interface PlayerAction {
        void run(Player player, CommandContext<CommandSourceStack> ctx) throws CivException;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    public static <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }

    /** Wraps an action: CivExceptions become localized error messages, bugs are logged. */
    public static Command<CommandSourceStack> run(Action action) {
        return ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            try {
                action.run(ctx);
                return Command.SINGLE_SUCCESS;
            } catch (CivException e) {
                Messages.get().send(sender, e.key(), e.args());
                return 0;
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Command failed: " + ctx.getInput(), e);
                Messages.get().send(sender, "error.internal");
                return 0;
            }
        };
    }

    /** Same as {@link #run} but requires a player sender. */
    public static Command<CommandSourceStack> player(PlayerAction action) {
        return run(ctx -> {
            if (!(ctx.getSource().getExecutor() instanceof Player player)) {
                throw new CivException("error.players-only");
            }
            action.run(player, ctx);
        });
    }

    public static boolean isPlayer(CommandSourceStack source) {
        return source.getExecutor() instanceof Player;
    }

    public static java.util.function.Predicate<CommandSourceStack> perm(String permission) {
        return source -> source.getSender().hasPermission(permission);
    }

    /** Suggests values from a supplier, filtered by the typed prefix (case-insensitive). */
    public static SuggestionProvider<CommandSourceStack> suggest(Supplier<Collection<String>> values) {
        return (ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String value : values.get()) {
                if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(value);
            }
            return builder.buildFuture();
        };
    }
}
