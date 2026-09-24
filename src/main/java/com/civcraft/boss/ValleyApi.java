package com.civcraft.boss;

import com.civcraft.core.CivException;
import java.time.Instant;
import org.bukkit.entity.Player;

/**
 * The Air valley and its world boss (spec 04 §11). The Portal building calls {@link #enter} after
 * checking ownership (own civilization only) and the {@code /civ perm} right. Other modules use
 * {@link #inValley} to switch off effects that do not work there (extra hearts, Olive wreath, some
 * artifacts).
 */
public interface ValleyApi {

    /** Teleports into the valley: full sky armour required, 30–45 s cooldown, all effects removed. */
    void enter(Player player) throws CivException;

    boolean inValley(Player player);

    /** Next scheduled boss spawn (for {@code /civ time}). */
    Instant nextBossSpawn();

    /** Next refill of the valley chests (for {@code /civ time}). */
    Instant nextChestFill();

    boolean bossAlive();
}
