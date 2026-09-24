package com.civcraft.event;

/** A town is about to be removed (burned down, disbanded, merged away). Fired before removal. */
public final class TownDisbandedEvent extends CivEvent {

    private final String townId;
    private final String civId;

    public TownDisbandedEvent(String townId, String civId) {
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
