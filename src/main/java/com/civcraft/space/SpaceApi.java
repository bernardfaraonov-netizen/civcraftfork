package com.civcraft.space;

import com.civcraft.model.Civilization;
import java.util.Map;

/** Space missions (spec 03 §9), implemented by {@link SpaceModule}. */
public interface SpaceApi {

    /** Number of missions completed so far (0..{@link #missionCount()}). */
    int completedMissions(Civilization civ);

    int missionCount();

    /** Whether a launched mission is collecting hammers and beakers right now. */
    boolean missionActive(Civilization civ);

    /**
     * While a mission runs, all civ science goes into it instead of research (spec 03 §9.1 [допущение]).
     * The research module calls this with the beakers it would otherwise have used.
     */
    void addMissionBeakers(Civilization civ, double beakers);

    /** A completed Space Shuttle stands in the civ's capital. */
    boolean hasShuttleInCapital(Civilization civ);

    /** Factory recipes of the shuttle components: component id → (ingredient id → amount). */
    Map<String, Map<String, Integer>> componentRecipes();
}
