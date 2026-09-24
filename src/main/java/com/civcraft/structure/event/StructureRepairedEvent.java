package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;

/** A destroyed structure finished its repair and works again. */
public final class StructureRepairedEvent extends CivEvent {

    private final String structureId;
    private final String type;
    private final String townId;

    public StructureRepairedEvent(String structureId, String type, String townId) {
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
