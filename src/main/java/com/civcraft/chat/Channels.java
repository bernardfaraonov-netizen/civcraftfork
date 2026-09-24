package com.civcraft.chat;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Camp;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * System announcements to town, civilization, camp and alliance members. Everything is resolved on the
 * main thread from the in-memory state.
 */
public final class Channels {

    private Channels() {
    }

    public static List<Player> townMembers(Town town) {
        List<Player> result = new ArrayList<>();
        for (UUID id : town.residents()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) result.add(p);
        }
        return result;
    }

    public static List<Player> civMembers(Civilization civ) {
        CivCraft c = CivCraft.get();
        List<Player> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Town t = c.state().townOf(c.state().resident(p));
            if (t != null && Objects.equals(t.civId(), civ.id())) result.add(p);
        }
        return result;
    }

    public static List<Player> campMembers(Camp camp) {
        List<Player> result = new ArrayList<>();
        for (UUID id : camp.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) result.add(p);
        }
        return result;
    }

    public static void town(Town town, String key, TagResolver... args) {
        if (town == null) return;
        Component msg = prefixed("chat.prefix.town-system", town.name(), key, args);
        townMembers(town).forEach(p -> p.sendMessage(msg));
    }

    public static void civ(Civilization civ, String key, TagResolver... args) {
        if (civ == null) return;
        Component msg = prefixed("chat.prefix.civ-system", civ.name(), key, args);
        civMembers(civ).forEach(p -> p.sendMessage(msg));
    }

    public static void camp(Camp camp, String key, TagResolver... args) {
        if (camp == null) return;
        Component msg = prefixed("chat.prefix.camp-system", camp.name(), key, args);
        campMembers(camp).forEach(p -> p.sendMessage(msg));
    }

    public static void global(String key, TagResolver... args) {
        Messages m = Messages.get();
        Component msg = m.prefix().append(m.component(key, args));
        Bukkit.getServer().sendMessage(msg);
    }

    /** Sends to an offline-safe resident (only if online). */
    public static void resident(Resident r, String key, TagResolver... args) {
        if (r == null) return;
        Player p = Bukkit.getPlayer(r.uuid());
        if (p != null) Messages.get().send(p, key, args);
    }

    private static Component prefixed(String prefixKey, String name, String key, TagResolver... args) {
        Messages m = Messages.get();
        return m.component(prefixKey, Messages.arg("name", name)).append(m.component(key, args));
    }
}
