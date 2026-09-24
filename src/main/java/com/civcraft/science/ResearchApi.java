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

    // --- additions (default implementations keep other implementors source compatible) -----------

    /** Tech currently being researched, or null. */
    default String currentTech(Civilization civ) {
        return null;
    }

    /** Progress of the current research, 0..1. */
    default double progress(Civilization civ) {
        return 0;
    }

    /**
     * Gate for content listed in a tech's {@code unlocks} (balance/techs.yml). Categories: structures,
     * upgrades, governments, items, units, artifacts, missions, wonders. Content that no tech unlocks is
     * always available; otherwise the civ needs at least one tech that lists it.
     */
    default boolean unlocked(Civilization civ, String category, String id) {
        return true;
    }

    /** Tech ids whose {@code unlocks.<category>} list contains the id (empty when ungated). */
    default Set<String> techsUnlocking(String category, String id) {
        return Set.of();
    }

    /** Removes the most recently researched techs (revolution: spec 01 §18.2 loses 3). */
    default void removeLastTechs(Civilization civ, int count) {
    }

    /** Era of a tech (for the Capitol progress bar colour: current/past/new era), -1 if unknown. */
    default int techEra(String techId) {
        return -1;
    }

    /** Russian name of an era index. */
    default String eraName(int era) {
        return String.valueOf(era);
    }

    /** Tag colour of the civ: its fixed colour ({@code /civ set ctag}) or the colour of its era. */
    default net.kyori.adventure.text.format.TextColor tagColor(Civilization civ) {
        return net.kyori.adventure.text.format.NamedTextColor.WHITE;
    }

    /** Highest era among all civilizations (the "leader" era of {@code /civ research era}). */
    default int leaderEra() {
        return 0;
    }

    /** Base HP of each Capitol control block for the civ's era (spec 03 §2, before talents/wonders). */
    default int capitolControlBlockHp(Civilization civ) {
        return 50;
    }

    /** Science of the civ per hour as used by research (after civ-wide multipliers). */
    default double beakersPerHour(Civilization civ) {
        return 0;
    }

    /** Tech the civ is researching right now, or null (used by the ruin technology scroll). */
    default String currentResearch(Civilization civ) {
        return currentTech(civ);
    }

    /** Beakers the civ needs in total for the tech (after discounts); 0 when unknown. */
    default double researchCost(Civilization civ, String techId) {
        return 0;
    }
}
