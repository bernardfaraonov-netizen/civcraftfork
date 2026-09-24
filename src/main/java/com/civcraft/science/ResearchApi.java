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
}
