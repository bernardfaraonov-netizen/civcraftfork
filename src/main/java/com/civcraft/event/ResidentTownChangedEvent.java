package com.civcraft.event;

import java.util.UUID;

/** A resident joined, left or changed town. Either id may be null. */
public final class ResidentTownChangedEvent extends CivEvent {

    private final UUID resident;
    private final String oldTownId;
    private final String newTownId;

    public ResidentTownChangedEvent(UUID resident, String oldTownId, String newTownId) {
        this.resident = resident;
        this.oldTownId = oldTownId;
        this.newTownId = newTownId;
    }

    public UUID resident() {
        return resident;
    }

    public String oldTownId() {
        return oldTownId;
    }

    public String newTownId() {
        return newTownId;
    }
}
