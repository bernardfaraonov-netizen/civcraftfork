package com.civcraft.protection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * One protection rule set (claims, structures, camps, war...). Guards are consulted in priority order;
 * the first non-PASS verdict wins.
 */
public interface Guard {

    /** Lower runs first. War rules run before claims so they can override them during war time. */
    int priority();

    /**
     * @param actor the player, or {@code null} for environment actions (explosions, pistons, fire...)
     * @param block the affected block
     * @param source for FLOW actions the block the change comes from (piston, liquid, dispenser), else null
     */
    Verdict check(Player actor, Action action, Block block, Block source);
}
