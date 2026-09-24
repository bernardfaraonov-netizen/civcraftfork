package com.civcraft.event;

/** A civilization adopted, changed or lost its religion. Religion ids are null for "none". */
public final class ReligionChangedEvent extends CivEvent {

    public enum Reason { ADOPTED, CHANGED, LOST, ADMIN }

    private final String civId;
    private final String oldReligion;
    private final String newReligion;
    private final Reason reason;

    public ReligionChangedEvent(String civId, String oldReligion, String newReligion, Reason reason) {
        this.civId = civId;
        this.oldReligion = oldReligion;
        this.newReligion = newReligion;
        this.reason = reason;
    }

    public String civId() {
        return civId;
    }

    public String oldReligion() {
        return oldReligion;
    }

    public String newReligion() {
        return newReligion;
    }

    public Reason reason() {
        return reason;
    }
}
