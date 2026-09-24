package com.civcraft.talent;

import com.civcraft.storage.Stored;
import java.util.HashMap;
import java.util.Map;

/** Talents of one civilization (collection {@code talents}). */
public final class TalentState implements Stored {

    private String civId;
    /** Level → chosen talent 1..3. */
    private Map<Integer, Integer> choices = new HashMap<>();
    /** Highest capital culture level ever reached (talents of lower unchosen levels are lost). */
    private int maxLevelReached;

    private TalentState() {
    }

    public TalentState(String civId) {
        this.civId = civId;
    }

    @Override
    public String storageId() {
        return civId;
    }

    void repair() {
        if (choices == null) choices = new HashMap<>();
        choices.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 1 || e.getValue() > 3);
    }

    public String civId() {
        return civId;
    }

    public Map<Integer, Integer> choices() {
        return choices;
    }

    public int maxLevelReached() {
        return maxLevelReached;
    }

    public void maxLevelReached(int level) {
        this.maxLevelReached = level;
    }
}
