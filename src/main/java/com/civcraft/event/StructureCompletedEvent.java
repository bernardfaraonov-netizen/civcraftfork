package com.civcraft.event;

/** A structure finished construction (or was completed by an admin). */
public final class StructureCompletedEvent extends CivEvent {

    private final String structureId;
    private final String type;
    private final String townId;

    public StructureCompletedEvent(String structureId, String type, String townId) {
        this.structureId = structureId;
        this.type = type;
        this.townId = townId;
    }

    public String structureId() {
        return structureId;
    }

    public String type() {
        return type;
    }

    public String townId() {
        return townId;
    }
}
