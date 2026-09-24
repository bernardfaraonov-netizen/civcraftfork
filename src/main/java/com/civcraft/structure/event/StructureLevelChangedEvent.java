package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;

/** The level of a structure changed (upgrade, production levels, war loss). */
public final class StructureLevelChangedEvent extends CivEvent {

    private final String structureId;
    private final String type;
    private final String townId;
    private final int oldLevel;
    private final int newLevel;

    public StructureLevelChangedEvent(String structureId, String type, String townId, int oldLevel, int newLevel) {
        this.structureId = structureId;
        this.type = type;
        this.townId = townId;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
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

    public int oldLevel() {
        return oldLevel;
    }

    public int newLevel() {
        return newLevel;
    }
}
