package com.civcraft.resident;

import com.civcraft.CivCraft;
import com.civcraft.command.Cmd;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Brigadier helpers shared by the resident, town, civ, plot and camp command trees. */
public final class CmdKit {

    /** Every literal of every CivCraft command (for the Russian-layout converter). */
    private static final Set<String> LITERALS = ConcurrentHashMap.newKeySet();

    private CmdKit() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        return Cmd.literal(name);
    }

    public static RequiredArgumentBuilder<CommandSourceStack, String> word(String name) {
        return Cmd.arg(name, StringArgumentType.word());
    }

    /** A single argument that may contain any characters except spaces (Cyrillic upgrade names etc.). */
    public static RequiredArgumentBuilder<CommandSourceStack, String> text(String name) {
        return Cmd.arg(name, StringArgumentType.greedyString());
    }

    public static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    public static SuggestionProvider<CommandSourceStack> towns() {
        return Cmd.suggest(() -> CivCraft.get().state().towns().stream().map(Town::name).toList());
    }

    public static SuggestionProvider<CommandSourceStack> civs() {
        return Cmd.suggest(() -> CivCraft.get().state().civs().stream().map(Civilization::name).toList());
    }

    public static SuggestionProvider<CommandSourceStack> camps() {
        return Cmd.suggest(() -> CivCraft.get().state().camps().stream().map(Camp::name).toList());
    }

    public static SuggestionProvider<CommandSourceStack> players() {
        return Cmd.suggest(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }

    public static SuggestionProvider<CommandSourceStack> values(Collection<String> values) {
        List<String> copy = List.copyOf(values);
        return Cmd.suggest(() -> copy);
    }

    public static SuggestionProvider<CommandSourceStack> values(Supplier<Collection<String>> values) {
        return Cmd.suggest(values);
    }

    /** Executes by printing the help text {@code key} (a list in the language file). */
    public static com.mojang.brigadier.Command<CommandSourceStack> help(String key) {
        return Cmd.run(ctx -> {
            Messages m = Messages.get();
            for (Component line : m.lines(key)) ctx.getSource().getSender().sendMessage(line);
        });
    }

    /**
     * Registers a root command with aliases through the Paper lifecycle. The tree is built lazily when
     * the command event fires (after all modules are enabled, so extensions from other modules are
     * visible).
     */
    public static void register(CivCraft civ, Supplier<LiteralArgumentBuilder<CommandSourceStack>> root,
                                String description, List<String> aliases) {
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = root.get().build();
            collect(node, new HashSet<>());
            LITERALS.add(node.getLiteral());
            LITERALS.addAll(aliases);
            event.registrar().register(node, description, new ArrayList<>(aliases));
        });
    }

    private static void collect(CommandNode<CommandSourceStack> node, Set<CommandNode<CommandSourceStack>> seen) {
        if (!seen.add(node)) return;
        if (node instanceof LiteralCommandNode<CommandSourceStack> lit) LITERALS.add(lit.getLiteral());
        for (CommandNode<CommandSourceStack> child : node.getChildren()) collect(child, seen);
    }

    public static boolean isLiteral(String s) {
        return LITERALS.contains(s);
    }
}
