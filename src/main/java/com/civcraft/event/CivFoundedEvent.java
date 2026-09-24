package com.civcraft.event;

/** A new civilization (province) was founded. */
public final class CivFoundedEvent extends CivEvent {

    private final String civId;

    public CivFoundedEvent(String civId) {
        this.civId = civId;
    }

    public String civId() {
        return civId;
    }
}
