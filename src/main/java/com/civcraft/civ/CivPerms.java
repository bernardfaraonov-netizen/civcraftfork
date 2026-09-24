package com.civcraft.civ;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * The {@code /civ perm} matrix (spec §7.5). Levels: OWNER (В), LEADERS (Л), ADVISERS (П), plus RESIDENTS
 * and ALL for wonder usage. Individual players can be granted a permission regardless of rank.
 * Defaults live in {@code balance/civ.yml perms}.
 */
public final class CivPerms {

    public enum Level {
        OWNER, LEADERS, ADVISERS, RESIDENTS, ALL;

        public static Level parse(String s) {
            String v = s.toUpperCase(Locale.ROOT);
            return switch (v) {
                case "OWNER", "OWNERS", "В" -> OWNER;
                case "LEADER", "LEADERS", "Л" -> LEADERS;
                case "ADVISER", "ADVISERS", "ADVISOR", "ADVISORS", "П" -> ADVISERS;
                case "RESIDENT", "RESIDENTS", "MEMBERS" -> RESIDENTS;
                case "ALL", "EVERYONE" -> ALL;
                default -> null;
            };
        }
    }

    private static final String ALL_FLAG = "perm-all:";
    private static final String RESIDENTS_FLAG = "perm-residents:";

    private CivPerms() {
    }

    private static CivCraft civ() {
        return CivCraft.get();
    }

    public static List<String> keys() {
        return new ArrayList<>(civ().balance().section("civ", "perms").getKeys(false));
    }

    public static Level defaultLevel(String perm) {
        ConfigurationSection s = civ().balance().section("civ", "perms");
        Level l = Level.parse(s.getString(perm, "LEADERS"));
        return l == null ? Level.LEADERS : l;
    }

    public static Level level(Civilization c, String perm) {
        if (c.flag(ALL_FLAG + perm)) return Level.ALL;
        if (c.flag(RESIDENTS_FLAG + perm)) return Level.RESIDENTS;
        Civilization.Rank rank = c.perms().get(perm);
        if (rank == null) return defaultLevel(perm);
        return switch (rank) {
            case OWNER -> Level.OWNER;
            case LEADER -> Level.LEADERS;
            case ADVISER -> Level.ADVISERS;
            case NONE -> Level.RESIDENTS;
        };
    }

    public static void set(Civilization c, String perm, Level level) {
        c.flag(ALL_FLAG + perm, level == Level.ALL);
        c.flag(RESIDENTS_FLAG + perm, level == Level.RESIDENTS);
        c.perms().put(perm, switch (level) {
            case OWNER -> Civilization.Rank.OWNER;
            case LEADERS -> Civilization.Rank.LEADER;
            case ADVISERS -> Civilization.Rank.ADVISER;
            case RESIDENTS, ALL -> Civilization.Rank.NONE;
        });
        civ().state().save(c);
    }

    /** Whether the player is a member of the civilization (resident of one of its towns). */
    public static boolean isMember(Civilization c, UUID player) {
        Resident r = civ().state().resident(player);
        Town t = civ().state().townOf(r);
        return t != null && Objects.equals(t.civId(), c.id());
    }

    public static boolean has(Civilization c, UUID player, String perm) {
        Set<UUID> personal = c.personalPerms().get(perm);
        if (personal != null && personal.contains(player)) return true;
        return switch (level(c, perm)) {
            case ALL -> true;
            case RESIDENTS -> isMember(c, player) || c.rank(player) != Civilization.Rank.NONE;
            case ADVISERS -> c.rank(player).atLeast(Civilization.Rank.ADVISER);
            case LEADERS -> c.rank(player).atLeast(Civilization.Rank.LEADER);
            case OWNER -> c.rank(player) == Civilization.Rank.OWNER;
        };
    }

    public static void check(Player p, Civilization c, String perm) throws CivException {
        if (c == null) throw new CivException("error.not-in-civ");
        if (!has(c, p.getUniqueId(), perm)) {
            throw new CivException("civ.perm.denied", Messages.arg("perm", perm),
                    Messages.arg("level", civ().messages().plain("civ.perm.level." + level(c, perm).name().toLowerCase(Locale.ROOT))));
        }
    }

    /**
     * Position ranking used for "only an equal or higher position may evict" (spec §6.5): owner 5,
     * leader 4, adviser 3, mayor 2, assistant 1, none 0.
     */
    public static int position(Civilization c, Town town, UUID player) {
        if (c != null) {
            switch (c.rank(player)) {
                case OWNER -> {
                    return 5;
                }
                case LEADER -> {
                    return 4;
                }
                case ADVISER -> {
                    return 3;
                }
                default -> {
                }
            }
        }
        if (town != null && town.isMayor(player)) return 2;
        if (town != null && town.isAssistant(player)) return 1;
        return 0;
    }
}
