package com.civcraft.science;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.town.TownApi;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Permission and membership helpers shared by science, talents, religion, space and victory. The
 * {@code /civ perm} matrix (spec 01 §7.5) is enforced by the town module through
 * {@link TownApi#checkCivPerm}; when it is not installed the defaults below are applied directly to the
 * civilization's perm map.
 */
public final class CivPerms {

    /** {@code /civ research} on/queue (advisers+; change additionally needs a leader). */
    public static final String RESEARCH = "research";
    /** {@code /civ religion} management other than founding/changing (leaders). */
    public static final String RELIGION = "religion";
    /** Founding and changing a religion (owner). */
    public static final String RELIGION_FOUND = "religion_found";
    /** Buying units and artifacts with religion points in the barracks (leaders). */
    public static final String RELIGION_PURCHASE = "religion_purchase";
    /** {@code /civ talent} (leaders). */
    public static final String TALENT = "talent";
    /** Launching space missions (leaders). */
    public static final String SPACE = "space";

    private CivPerms() {
    }

    public static Civilization.Rank defaultRank(String perm) {
        return switch (perm) {
            case RESEARCH -> Civilization.Rank.ADVISER;
            case RELIGION_FOUND -> Civilization.Rank.OWNER;
            default -> Civilization.Rank.LEADER;
        };
    }

    /** Throws {@code error.no-permission} unless the player holds the civ permission. */
    public static void check(CivCraft civ, Player player, Civilization c, String perm) throws CivException {
        if (player.hasPermission("civcraft.admin")) return;
        TownApi towns = civ.apiOrNull(TownApi.class);
        if (towns != null) {
            towns.checkCivPerm(player, c, perm);
            return;
        }
        if (!allowedByMatrix(c, player.getUniqueId(), perm)) throw new CivException("error.no-permission");
    }

    public static boolean allowed(CivCraft civ, Player player, Civilization c, String perm) {
        try {
            check(civ, player, c, perm);
            return true;
        } catch (CivException e) {
            return false;
        }
    }

    private static boolean allowedByMatrix(Civilization c, UUID uuid, String perm) {
        Set<UUID> personal = c.personalPerms().get(perm);
        if (personal != null && personal.contains(uuid)) return true;
        Civilization.Rank need = c.perms().getOrDefault(perm, defaultRank(perm));
        return c.rank(uuid).atLeast(need);
    }

    /** Requires a hard rank (e.g. {@code /civ research change} needs a leader regardless of the matrix). */
    public static void requireRank(Player player, Civilization c, Civilization.Rank rank) throws CivException {
        if (player.hasPermission("civcraft.admin")) return;
        if (!c.rank(player.getUniqueId()).atLeast(rank)) throw new CivException("error.no-permission");
    }

    /** The civilization of the player's town, or a localized error. */
    public static Civilization civOf(CivCraft civ, Player player) throws CivException {
        Resident r = civ.state().resident(player);
        if (r == null || !r.hasTown()) throw new CivException("error.not-in-town");
        Civilization c = civ.state().civOf(r);
        if (c == null) throw new CivException("error.not-in-civ");
        return c;
    }

    public static Town townOf(CivCraft civ, Player player) throws CivException {
        TownApi towns = civ.apiOrNull(TownApi.class);
        if (towns != null) return towns.selectedTown(player);
        Town t = civ.state().townOf(player);
        if (t == null) throw new CivException("error.not-in-town");
        return t;
    }

    /** Sends a message to every online member of the civilization. */
    public static void tellCiv(CivCraft civ, Civilization c, String key, TagResolver... args) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Civilization pc = civ.state().civOf(p);
            if (pc != null && pc.id().equals(c.id())) civ.messages().send(p, key, args);
        }
    }

    /** Sends a message to online owner and leaders of the civilization. */
    public static void tellLeaders(CivCraft civ, Civilization c, String key, TagResolver... args) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (c.isLeader(p.getUniqueId())) civ.messages().send(p, key, args);
        }
    }

    public static void broadcast(CivCraft civ, String key, TagResolver... args) {
        Messages m = civ.messages();
        Bukkit.getServer().broadcast(m.prefix().append(m.component(key, args)));
    }
}
