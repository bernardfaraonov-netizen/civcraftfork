package com.civcraft.event;

/** A civilization chose a talent (spec 01 §15). */
public final class TalentChosenEvent extends CivEvent {

    private final String civId;
    private final int level;
    private final int choice;

    public TalentChosenEvent(String civId, int level, int choice) {
        this.civId = civId;
        this.level = level;
        this.choice = choice;
    }

    public String civId() {
        return civId;
    }

    public int level() {
        return level;
    }

    public int choice() {
        return choice;
    }
}
