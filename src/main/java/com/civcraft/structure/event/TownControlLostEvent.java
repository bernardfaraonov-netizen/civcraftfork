package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;
import org.bukkit.entity.Player;

/**
 * Every control block of a town (main building plus Neuschwanstein) is destroyed: the war module captures the town
 * (spec 02 §2.4 "Когда сломан последний КБ, город автоматически захвачен").
 */
public final class TownControlLostEvent extends CivEvent {

    private final String townId;
    private final String structureId;
    private final Player attacker;

    public TownControlLostEvent(String townId, String structureId, Player attacker) {
        this.townId = townId;
        this.structureId = structureId;
        this.attacker = attacker;
    }

    public String townId() {
        return townId;
    }

    /** The structure whose control block was broken last. */
    public String structureId() {
        return structureId;
    }

    /** The player who broke it, or null. */
    public Player attacker() {
        return attacker;
    }
}
