package com.civcraft.event;

/**
 * The era of a civilization changed (spec 03 §2: the era is the highest era of its researched techs).
 * Listeners update tag colours, Capitol control block HP and similar era-dependent values.
 */
public final class EraChangedEvent extends CivEvent {

    private final String civId;
    private final int oldEra;
    private final int newEra;

    public EraChangedEvent(String civId, int oldEra, int newEra) {
        this.civId = civId;
        this.oldEra = oldEra;
        this.newEra = newEra;
    }

    public String civId() {
        return civId;
    }

    public int oldEra() {
        return oldEra;
    }

    public int newEra() {
        return newEra;
    }
}
