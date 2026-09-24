package com.civcraft.victory;

import com.civcraft.model.Civilization;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Victories (spec 03 §11), implemented by {@link VictoryModule}. Other modules use it for the rules that
 * apply while a countdown runs (war declaration windows, renaming and owner change bans...).
 */
public interface VictoryApi {

    /** Victory type ids: religious, economic, diplomatic, cultural, scientific, domination. */
    List<String> types();

    record Countdown(String civId, String type, Instant startedAt, Duration remaining, boolean paused) {
    }

    record HallRecord(String phase, String server, String civ, String type, String leader, String date) {
    }

    List<Countdown> countdowns();

    /** Shortest remaining countdown of the civ, or null when it is not heading to a victory. */
    Duration timeToVictory(Civilization civ);

    /** Whether any civilization is in a victory countdown (e.g. war declaration within 24 h). */
    boolean anyCountdown();

    /** A victory was declared and the phase is over. */
    boolean phaseOver();

    /** "[WON]" prefix of a past winner (owner of a winning civ), or null. */
    Component wonPrefix(UUID player);

    /** Civilization score used by the domination victory. */
    long score(Civilization civ);

    List<HallRecord> hall();
}
