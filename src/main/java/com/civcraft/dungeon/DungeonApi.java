package com.civcraft.dungeon;

import com.civcraft.core.CivException;
import org.bukkit.entity.Player;

/**
 * The dungeon (spec 04 §12). The Sewer building calls {@link #enter} after checking that the sewer
 * belongs to the player's civilization and the {@code /civ perm} right.
 */
public interface DungeonApi {

    /** Teleports into the dungeon: cooldown, effects removed, blindness 3 s. */
    void enter(Player player) throws CivException;

    boolean inDungeon(Player player);
}
