package com.civcraft.event;

/** A structure was destroyed in war, demolished, or removed with its town. */
public final class StructureDestroyedEvent extends CivEvent {

    public enum Cause { WAR, DEMOLISHED, DISBANDED, ADMIN, CAPTURED, REPLACED }

    private final String structureId;
    private final String type;
    private final String townId;
    private final Cause cause;

    public StructureDestroyedEvent(String structureId, String type, String townId, Cause cause) {
        this.structureId = structureId;
        this.type = type;
        this.townId = townId;
        this.cause = cause;
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

    public Cause cause() {
        return cause;
    }
}
