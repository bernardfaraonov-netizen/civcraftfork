package com.civcraft.chat;

import static com.civcraft.resident.CmdKit.lit;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.Cmd;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.resident.CmdKit;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Town, civilization, alliance and camp chat channels (spec §19.6: /tc, /cc, /ac, /camp chat).
 * The async chat event only reads a concurrent map of chat modes and hands the text to the main
 * thread; all game state is read there (legacy processed chat against game state on the chat thread).
 */
public final class ChatModule implements Module, Listener {

    public enum Channel { GLOBAL, TOWN, CIV, ALLY, CAMP }

    private CivCraft civ;
    private final Map<UUID, Channel> modes = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("chat");
    }

    @Override
    public void enable(CivCraft civ) {
        civ.listen(this);
        CmdKit.register(civ, () -> channelCommand("tc", Channel.TOWN), "Town chat", List.of());
        CmdKit.register(civ, () -> channelCommand("cc", Channel.CIV), "Civilization chat", List.of());
        CmdKit.register(civ, () -> channelCommand("ac", Channel.ALLY), "Alliance chat", List.of());
        CmdKit.register(civ, () -> lit("gc").executes(Cmd.player((p, ctx) -> {
            modes.remove(p.getUniqueId());
            civ.messages().send(p, "chat.mode.global");
        })), "Global chat", List.of());
    }

    private LiteralArgumentBuilder<CommandSourceStack> channelCommand(String name, Channel channel) {
        return lit(name)
                .executes(Cmd.player((p, ctx) -> toggle(p, channel)))
                .then(CmdKit.text("message").executes(Cmd.player((p, ctx) -> send(p, channel, CmdKit.arg(ctx, "message")))));
    }

    /** Switches the player's chat mode (used by /tc, /cc, /ac and /camp chat). */
    public void toggle(Player p, Channel channel) throws CivException {
        check(p, channel);
        if (modes.get(p.getUniqueId()) == channel) {
            modes.remove(p.getUniqueId());
            civ.messages().send(p, "chat.mode.global");
        } else {
            modes.put(p.getUniqueId(), channel);
            civ.messages().send(p, "chat.mode." + channel.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public Channel mode(Player p) {
        return modes.getOrDefault(p.getUniqueId(), Channel.GLOBAL);
    }

    public void reset(UUID player) {
        modes.remove(player);
    }

    private void check(Player p, Channel channel) throws CivException {
        Resident r = civ.state().resident(p);
        switch (channel) {
            case TOWN -> {
                if (civ.state().townOf(r) == null) throw new CivException("error.not-in-town");
            }
            case CIV, ALLY -> {
                Civilization c = civ.state().civOf(r);
                if (c == null) throw new CivException("error.not-in-civ");
                MuteRegistry mutes = civ.apiOrNull(MuteRegistry.class);
                if (mutes != null && mutes.isMuted(c, p.getUniqueId())) throw new CivException("chat.muted");
            }
            case CAMP -> {
                if (r == null || civ.state().camp(r.campId()) == null) throw new CivException("camp.not-member");
            }
            default -> {
            }
        }
    }

    /** Sends one message to a channel (main thread). */
    public void send(Player p, Channel channel, String text) throws CivException {
        check(p, channel);
        Resident r = civ.state().resident(p);
        Messages m = civ.messages();
        List<Player> recipients = new ArrayList<>();
        String hideKey;
        Component line;
        switch (channel) {
            case TOWN -> {
                Town t = civ.state().townOf(r);
                recipients.addAll(Channels.townMembers(t));
                hideKey = "hide-chat-town";
                line = m.component("chat.format.town", Messages.arg("town", t.name()), Messages.arg("name", p.getName()),
                        Messages.arg("message", text));
            }
            case CIV -> {
                Civilization c = civ.state().civOf(r);
                recipients.addAll(Channels.civMembers(c));
                hideKey = "hide-chat-civ";
                line = m.component("chat.format.civ", Messages.arg("civ", c.tag() == null ? c.name() : c.tag()),
                        Messages.arg("name", p.getName()), Messages.arg("message", text));
            }
            case ALLY -> {
                Civilization c = civ.state().civOf(r);
                recipients.addAll(Channels.civMembers(c));
                DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
                if (dip != null) {
                    for (String ally : dip.allies(c.id())) {
                        Civilization a = civ.state().civ(ally);
                        if (a != null) recipients.addAll(Channels.civMembers(a));
                    }
                }
                hideKey = "hide-chat-ally";
                line = m.component("chat.format.ally", Messages.arg("civ", c.tag() == null ? c.name() : c.tag()),
                        Messages.arg("name", p.getName()), Messages.arg("message", text));
            }
            case CAMP -> {
                Camp camp = civ.state().camp(r.campId());
                recipients.addAll(Channels.campMembers(camp));
                hideKey = "hide-chat-camp";
                line = m.component("chat.format.camp", Messages.arg("camp", camp.name()), Messages.arg("name", p.getName()),
                        Messages.arg("message", text));
            }
            default -> {
                p.chat(text);
                return;
            }
        }
        Set<UUID> seen = new java.util.HashSet<>();
        for (Player target : recipients) {
            if (!seen.add(target.getUniqueId())) continue;
            Resident tr = civ.state().resident(target);
            if (tr != null && tr.setting(hideKey) && !target.equals(p)) continue;
            target.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Channel channel = modes.get(e.getPlayer().getUniqueId());
        if (channel == null || channel == Channel.GLOBAL) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.originalMessage());
        Player p = e.getPlayer();
        civ.tasks().sync(() -> {
            if (!p.isOnline()) return;
            try {
                send(p, channel, text);
            } catch (CivException ex) {
                modes.remove(p.getUniqueId());
                civ.messages().send(p, ex.key(), ex.args());
            }
        });
    }
}
