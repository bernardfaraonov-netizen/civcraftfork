package com.civcraft.event;

/** The effective government of a civilization changed (anarchy start or transition end). */
public final class GovernmentChangedEvent extends CivEvent {

    private final String civId;
    private final String oldGovernment;
    private final String newGovernment;

    public GovernmentChangedEvent(String civId, String oldGovernment, String newGovernment) {
        this.civId = civId;
        this.oldGovernment = oldGovernment;
        this.newGovernment = newGovernment;
    }

    public String civId() {
        return civId;
    }

    public String oldGovernment() {
        return oldGovernment;
    }

    public String newGovernment() {
        return newGovernment;
    }
}
