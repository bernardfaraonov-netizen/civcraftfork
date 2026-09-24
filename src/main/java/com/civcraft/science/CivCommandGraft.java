package com.civcraft.science;

import com.civcraft.CivCraft;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Attaches the science family's sub-commands ({@code research}, {@code talent}, {@code religion},
 * {@code space}, {@code victory}, {@code artifact}) to {@code /civ} (alias {@code /c}).
 * <p>
 * {@code /civ} itself belongs to the civilization module. Paper replaces a root command when the same
 * label is registered twice, so instead of registering our own root we run late in the COMMANDS
 * lifecycle event and add our literals as children of the existing root node (and of its alias
 * redirect targets). If no {@code /civ} exists at that point, a root containing only our sub-commands is
 * registered so the features stay reachable.
 */
public final class CivCommandGraft {

    /** Runs after default-priority handlers (the civ module's root registration). */
    private static final int PRIORITY = 1000;

    private static final List<Supplier<LiteralArgumentBuilder<CommandSourceStack>>> CONTRIBUTIONS = new ArrayList<>();
    private static CivCraft owner;

    private CivCommandGraft() {
    }

    public static synchronized void add(CivCraft civ, Supplier<LiteralArgumentBuilder<CommandSourceStack>> sub) {
        if (owner != civ) {
            CONTRIBUTIONS.clear();
            owner = civ;
            civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(
                    event -> graft(civ, event.registrar())).priority(PRIORITY));
        }
        CONTRIBUTIONS.add(sub);
    }

    private static void graft(CivCraft civ, Commands commands) {
        CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
        List<CommandNode<CommandSourceStack>> targets = new ArrayList<>();
        for (String label : List.of("civcraft:civ", "civ", "civcraft:c", "c")) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(label);
            if (node == null) continue;
            CommandNode<CommandSourceStack> target = node.getRedirect() != null ? node.getRedirect() : node;
            boolean known = false;
            for (CommandNode<CommandSourceStack> t : targets) known |= t == target;
            if (!known) targets.add(target);
        }
        if (targets.isEmpty()) {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("civ");
            for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> sub : CONTRIBUTIONS) root.then(sub.get());
            root.executes(ctx -> {
                ctx.getSource().getSender().sendMessage(civ.messages().component("science.civ-help"));
                return 1;
            });
            commands.register(root.build(), "CivCraft: civilization", List.of("c"));
            civ.logger().info("No /civ root found; registered science sub-commands under a standalone /civ");
            return;
        }
        for (CommandNode<CommandSourceStack> target : targets) {
            for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> sub : CONTRIBUTIONS) {
                target.addChild(sub.get().build());
            }
        }
    }
}
