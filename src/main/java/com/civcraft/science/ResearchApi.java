package com.civcraft.science;

import com.civcraft.model.Civilization;
import java.util.Set;

/** Technology state of civilizations, implemented by the science module. */
public interface ResearchApi {

    boolean hasTech(Civilization civ, String techId);

    /** All researched tech ids. */
    Set<String> techs(Civilization civ);

    /** Current era index 0..7 (spec 03 eras). */
    int era(Civilization civ);

    /** Whether the tech id exists in the tech tree (for validating balance files). */
    boolean techExists(String techId);

    /** Russian display name of a tech. */
    String techName(String techId);

    /** Adds beakers to the civ's current research (spy missions, scrolls, space missions...). */
    void addBeakers(Civilization civ, double beakers);

    /** Tech the civ is researching right now, or null (used by the ruin technology scroll). */
    default String currentResearch(Civilization civ) {
        return null;
    }

    /** Beakers the civ needs in total for the tech (after discounts); 0 when unknown. */
    default double researchCost(Civilization civ, String techId) {
        return 0;
    }

    /** Era index of a tech on the same scale as {@link #era}; -1 when unknown. */
    default int techEra(String techId) {
        return -1;
    }
}
