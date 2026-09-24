package com.civcraft.event;

/** A town moved to another civilization (gift, market, liberation, capitulation, revolution). */
public final class TownOwnerChangedEvent extends CivEvent {

    private final String townId;
    private final String oldCivId;
    private final String newCivId;

    public TownOwnerChangedEvent(String townId, String oldCivId, String newCivId) {
        this.townId = townId;
        this.oldCivId = oldCivId;
        this.newCivId = newCivId;
    }

    public String townId() {
        return townId;
    }

    public String oldCivId() {
        return oldCivId;
    }

    public String newCivId() {
        return newCivId;
    }
}
