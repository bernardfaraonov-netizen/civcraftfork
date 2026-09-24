package com.civcraft.town;

import com.civcraft.core.CivException;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import org.bukkit.entity.Player;

/**
 * Town economy and production figures, implemented by the town module. All values are final
 * (biomes + effects + government + happiness applied) and per hour unless stated otherwise.
 */
public interface TownApi {

    double hammersPerHour(Town town);

    double beakersPerHour(Town town);

    double culturePerHour(Town town);

    double faithPerHour(Town town);

    /** Hourly coin income before civ taxes, in coins. */
    double incomePerHour(Town town);

    /** Growth percent used by farms. */
    double growth(Town town);

    /** Happiness percent 0..100. */
    double happinessPercent(Town town);

    /** Multiplier of the current happiness state (0.2 … 1.5). */
    double happinessMultiplier(Town town);

    /** Number of improvement slots available to the town. */
    int slots(Town town);

    /** Daily upkeep in hundredths of a coin (town level + structures, all multipliers). */
    long dailyUpkeep(Town town);

    /** Current culture level. */
    int cultureLevel(Town town);

    /** Whether the player may manage the town (mayor, assistant, or civ leader). */
    boolean canManage(Player player, Town town);

    /**
     * Checks a {@code /civ perm} permission (spec §7.5) for the player; throws a localized error if
     * denied.
     */
    void checkCivPerm(Player player, Civilization civ, String perm) throws CivException;

    /** Town the player is managing: their selected town, else their own town. */
    Town selectedTown(Player player) throws CivException;
}
