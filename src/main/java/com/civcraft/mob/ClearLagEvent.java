package com.civcraft.mob;

import com.civcraft.event.CivEvent;

/** Fired right after ClearLag removed mobs and items (the dungeon respawns its rats on it). */
public final class ClearLagEvent extends CivEvent {

    private final int removedEntities;

    public ClearLagEvent(int removedEntities) {
        this.removedEntities = removedEntities;
    }

    public int removedEntities() {
        return removedEntities;
    }
}
