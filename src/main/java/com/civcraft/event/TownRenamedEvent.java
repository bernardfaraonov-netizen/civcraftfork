package com.civcraft.event;

/** A town was renamed (/rename town). */
public final class TownRenamedEvent extends CivEvent {

    private final String townId;
    private final String oldName;
    private final String newName;

    public TownRenamedEvent(String townId, String oldName, String newName) {
        this.townId = townId;
        this.oldName = oldName;
        this.newName = newName;
    }

    public String townId() {
        return townId;
    }

    public String oldName() {
        return oldName;
    }

    public String newName() {
        return newName;
    }
}
