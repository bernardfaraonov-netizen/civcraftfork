package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;

/** Construction of a structure started (after validation and payment). */
public final class StructurePlacedEvent extends CivEvent {

    private final String structureId;
    private final String type;
    private final String townId;

    public StructurePlacedEvent(String structureId, String type, String townId) {
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
