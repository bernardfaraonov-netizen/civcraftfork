package com.civcraft.event;

/** A province became a full civilization (its Capitol was completed). */
public final class CivFormedEvent extends CivEvent {

    private final String civId;

    public CivFormedEvent(String civId) {
        this.civId = civId;
    }

    public String civId() {
        return civId;
    }
}
