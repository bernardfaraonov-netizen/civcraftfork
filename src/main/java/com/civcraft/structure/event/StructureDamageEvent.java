package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/** A structure is about to lose hit points. Listeners may change the amount or cancel. */
public final class StructureDamageEvent extends CivEvent implements Cancellable {

    private final String structureId;
    private final String type;
    private final String townId;
    private final Player attacker;
    private int amount;
    private boolean cancelled;

    public StructureDamageEvent(String structureId, String type, String townId, Player attacker, int amount) {
        this.structureId = structureId;
        this.type = type;
        this.townId = townId;
        this.attacker = attacker;
        this.amount = amount;
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

    /** The attacking player, or null (explosion, cannon, admin). */
    public Player attacker() {
        return attacker;
    }

    public int amount() {
        return amount;
    }

    public void amount(int amount) {
        this.amount = Math.max(0, amount);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
