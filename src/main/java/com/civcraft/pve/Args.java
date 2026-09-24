package com.civcraft.pve;

import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Small argument helpers for the PvE commands. */
public final class Args {

    private Args() {
    }

    /** A word argument suggesting online player names. */
    public static RequiredArgumentBuilder<CommandSourceStack, String> onlinePlayer(String name) {
        return Cmd.arg(name, StringArgumentType.word())
                .suggests(Cmd.suggest(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()));
    }

    public static Player player(CommandContext<CommandSourceStack> ctx, String name) throws CivException {
        String value = StringArgumentType.getString(ctx, name);
        Player p = Bukkit.getPlayerExact(value);
        if (p == null) throw new CivException("error.unknown-player", Messages.arg("name", value));
        return p;
    }
}
