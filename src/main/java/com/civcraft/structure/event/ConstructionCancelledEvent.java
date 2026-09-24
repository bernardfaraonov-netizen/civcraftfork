package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;

/**
 * A structure under construction was removed before completion. Completed structures fire
 * {@link com.civcraft.event.StructureDestroyedEvent} instead.
 */
public final class ConstructionCancelledEvent extends CivEvent {

    public enum Reason { CANCELLED, WONDER_RACE_LOST, DESTROYED, ADMIN, DISBANDED }

    private final String structureId;
    private final String type;
    private final String townId;
    private final Reason reason;

    public ConstructionCancelledEvent(String structureId, String type, String townId, Reason reason) {
        this.structureId = structureId;
        this.type = type;
        this.townId = townId;
        this.reason = reason;
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

    public Reason reason() {
        return reason;
    }
}
