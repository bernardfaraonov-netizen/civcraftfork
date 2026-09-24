package com.civcraft.event;

/** A new town was founded (capital of a new province or via a settler). */
public final class TownFoundedEvent extends CivEvent {

    private final String townId;
    private final String civId;

    public TownFoundedEvent(String townId, String civId) {
        this.townId = townId;
        this.civId = civId;
    }

    public String townId() {
        return townId;
    }

    public String civId() {
        return civId;
    }
}
