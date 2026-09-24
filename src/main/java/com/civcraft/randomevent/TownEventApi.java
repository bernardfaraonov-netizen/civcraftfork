package com.civcraft.randomevent;

import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import java.time.Instant;
import net.kyori.adventure.audience.Audience;

/** Random town events (spec 04 §14). Used by {@code /t event} and {@code /civ events}. */
public interface TownEventApi {

    /** Id of the town's current event (e.g. {@code black_soil}), or null. */
    String activeEvent(Town town);

    /** When the current event (including its effect) ends, or null. */
    Instant activeUntil(Town town);

    /** Prints the town's event with its progress/hint ({@code /t event}). */
    void describe(Audience audience, Town town);

    /** Prints the events of every town of the civilization ({@code /civ events}). */
    void describe(Audience audience, Civilization civ);
}
