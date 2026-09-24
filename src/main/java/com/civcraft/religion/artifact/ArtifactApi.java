package com.civcraft.religion.artifact;

import com.civcraft.core.CivException;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Artifacts (spec 03 §8), implemented by {@link ArtifactModule}. The barracks (structure module) trains
 * them with coins and hammers and hands out {@link #create}; war/tower code queries the combat hooks.
 */
public interface ArtifactApi {

    Set<String> ids();

    boolean exists(String id);

    String name(String id);

    /** Hammers needed to produce the artifact in the barracks. */
    double hammers(String id);

    /** Coin price (hundredths) in the town, with the Mall discount when the town has one. */
    long coinPrice(String id, Town town);

    /** Religion-point price for an instant purchase, or -1 when it cannot be bought for religion. */
    double religionPrice(String id);

    /** Tavern in the town and the artifact's tech (or built wonder) in the civ. */
    void checkCanProduce(Civilization civ, Town town, String id) throws CivException;

    /** New artifact stack; single-use copies (ruins) vanish after one use. */
    ItemStack create(String id, boolean singleUse);

    /** Timed effect of an activated artifact is running (nanoplasts, invisibility_cap, archer...). */
    boolean isActive(Player player, String id);

    /** Passive artifact carried and working (the carry limit is respected). */
    boolean carries(Player player, String id);

    int maxCarried(Player player);

    /** Multiplier for damage from any tower (0.5 with active Nanoplasts). */
    double towerDamageMultiplier(Player player);

    /** Enemy scout towers and ships must not report the player (active Invisibility Cap). */
    boolean hiddenFromScouts(Player player);

    /** Extra damage to structures and wonders (Engineer); 0 against a civ with the Council of Eight. */
    int structureDamageBonus(Player player, Civilization target);

    /** Extra damage to control blocks (Conqueror). */
    int controlBlockDamageBonus(Player player, Civilization target);

    /** Chance that armor and weapons keep their durability on death (Reinforced Tools). */
    double deathDurabilitySaveChance(Player player);
}
