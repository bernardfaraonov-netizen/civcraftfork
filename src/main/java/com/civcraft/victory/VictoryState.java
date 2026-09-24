package com.civcraft.victory;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Global victory document (collection {@code victory}, id {@code state}). */
public final class VictoryState implements Stored {

    static final class Countdown {
        String civId;
        String type;
        Instant startedAt;
        /** Set while the countdown is paused (scientific victory without a shuttle). */
        Instant pausedAt;
        long pausedMillis;

        Countdown() {
        }

        Countdown(String civId, String type) {
            this.civId = civId;
            this.type = type;
            this.startedAt = Instant.now();
        }

        long elapsedMillis(Instant now) {
            Instant end = pausedAt != null ? pausedAt : now;
            return Math.max(0, end.toEpochMilli() - startedAt.toEpochMilli() - pausedMillis);
        }
    }

    static final class Winner {
        List<String> civIds = new ArrayList<>();
        List<String> civNames = new ArrayList<>();
        String type;
        Instant at;
        boolean draw;
    }

    static final class Record {
        String phase;
        String server;
        String civ;
        String type;
        String leader;
        String date;
    }

    private Instant phaseStart;
    private List<Countdown> countdowns = new ArrayList<>();
    private Winner winner;
    private List<Record> hall = new ArrayList<>();
    /** Player UUID → colour of their [WON] prefix. */
    private Map<String, String> wonPrefixes = new HashMap<>();
    private int colorIndex;

    @Override
    public String storageId() {
        return "state";
    }

    void repair() {
        if (countdowns == null) countdowns = new ArrayList<>();
        countdowns.removeIf(c -> c == null || c.civId == null || c.type == null || c.startedAt == null);
        if (hall == null) hall = new ArrayList<>();
        if (wonPrefixes == null) wonPrefixes = new HashMap<>();
    }

    Instant phaseStart() {
        return phaseStart;
    }

    void phaseStart(Instant at) {
        this.phaseStart = at;
    }

    List<Countdown> countdowns() {
        return countdowns;
    }

    Winner winner() {
        return winner;
    }

    void winner(Winner w) {
        this.winner = w;
    }

    List<Record> hall() {
        return hall;
    }

    Map<String, String> wonPrefixes() {
        return wonPrefixes;
    }

    int nextColorIndex() {
        return colorIndex++;
    }
}
