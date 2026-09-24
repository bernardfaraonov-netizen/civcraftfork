package com.civcraft.event;

/** A civilization is about to be removed. Fired before removal. */
public final class CivDisbandedEvent extends CivEvent {

    private final String civId;

    public CivDisbandedEvent(String civId) {
        this.civId = civId;
    }

    public String civId() {
        return civId;
    }
}
