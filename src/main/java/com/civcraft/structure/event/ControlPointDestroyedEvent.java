package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;
import org.bukkit.entity.Player;

/** A control block reached 0 hit points. */
public final class ControlPointDestroyedEvent extends CivEvent {

    private final String structureId;
    private final String townId;
    private final int index;
    private final Player attacker;

    public ControlPointDestroyedEvent(String structureId, String townId, int index, Player attacker) {
        this.structureId = structureId;
        this.townId = townId;
        this.index = index;
        this.attacker = attacker;
    }

    public String structureId() {
        return structureId;
    }

    public String townId() {
        return townId;
    }

    public int index() {
        return index;
    }

    /** The player who broke it, or null. */
    public Player attacker() {
        return attacker;
    }
}
