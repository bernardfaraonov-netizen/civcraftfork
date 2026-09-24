package com.civcraft.talent;

import com.civcraft.model.Civilization;
import java.util.Map;

/** Civilization talents (spec 01 §15), implemented by {@link TalentModule}. */
public interface TalentApi {

    /** Chosen talent (1..3) of the level, or 0. */
    int choice(Civilization civ, int level);

    default boolean has(Civilization civ, int level, int choice) {
        return choice(civ, level) == choice;
    }

    /** Level → chosen talent. */
    Map<Integer, Integer> choices(Civilization civ);

    int chosenCount(Civilization civ);

    /** Level whose talent can be chosen now, or 0 when nothing is pending. */
    int availableLevel(Civilization civ);
}
