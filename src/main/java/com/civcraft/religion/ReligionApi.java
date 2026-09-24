package com.civcraft.religion;

import com.civcraft.core.CivException;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;

/**
 * Religion points and religions of civilizations (spec 01 §16), implemented by {@link ReligionModule}.
 * Other modules use it for purchases with religion points (barracks), the Inquisition spy mission and
 * war losses.
 */
public interface ReligionApi {

    /** Accumulated religion points. */
    double points(Civilization civ);

    /** Takes points if the civ has enough; returns false and changes nothing otherwise. */
    boolean spend(Civilization civ, double amount);

    /** Adds points without modifiers (events, scrolls, prophet). */
    void add(Civilization civ, double amount);

    /** Removes up to {@code amount} points (Inquisition, war); returns the amount actually removed. */
    double remove(Civilization civ, double amount);

    /** Adopted religion id or null. */
    String religion(Civilization civ);

    /** Russian name of a religion id. */
    String religionName(String religionId);

    /** Place of the civ in its religion's rating (1-based), 0 without religion. */
    int place(Civilization civ);

    /** Strength of the religion buff 0..1 by place (spec 01 §16.3). */
    double strength(Civilization civ);

    /** Religion-point price of instantly buying a unit or artifact that costs {@code hammers}. */
    double purchasePrice(double hammers);

    /** Whether the civ could order a Prophet in the town (religion adopted, Notre-Dame). */
    void checkCanTrainProphet(Civilization civ, Town town) throws CivException;

    /** Points needed to found (adopt) a religion now. */
    double foundingCost(Civilization civ);
}
