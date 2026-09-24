package com.civcraft.diplomacy;

import com.civcraft.model.RelationType;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Diplomatic state machine (spec §17), implemented by the diplomacy module. The war-time combat
 * itself belongs to the war module; this API only owns relations and the war calendar view.
 */
public interface DiplomacyApi {

    /** Relation between two civilizations; a civ is ALLY with itself; no record means NEUTRAL. */
    RelationType relation(String civA, String civB);

    /** Whether the two civilizations are at war with each other. */
    boolean isAtWar(String civA, String civB);

    /** Whether the civilization is in at least one war. */
    boolean isAtWar(String civId);

    /** Whether the civilization declared at least one of its current wars (it pays war upkeep). */
    boolean isAggressor(String civId);

    /** Civilizations at war with the given one. */
    Set<String> enemies(String civId);

    /** Civilizations allied with the given one. */
    Set<String> allies(String civId);

    /** Whether the weekly war window is running now. */
    boolean isWarTime();

    /** Start of the next war window (or of the current one while it runs). */
    Instant nextWarStart();

    /** Whether the next war window starts within {@code window} (true during war time too). */
    boolean isWarWithin(Duration window);

    /** Whether any victory countdown is running (relaxes several war-declaration deadlines). */
    boolean victoryRunning();

    /**
     * Sets a relation directly (war engine, admin tools), firing the relation events. NEUTRAL removes
     * the record. {@code aggressor} is only meaningful for WAR/HOSTILE.
     */
    void setRelation(String civA, String civB, RelationType type, String aggressor);

    /** Ends a war between the two civilizations (they become PEACE). No-op if they are not at war. */
    void endWar(String civA, String civB);
}
