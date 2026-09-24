package com.civcraft.structure.event;

import com.civcraft.event.CivEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/** A control block is about to be damaged. Listeners may change the amount or cancel. */
public final class ControlPointDamageEvent extends CivEvent implements Cancellable {

    private final String structureId;
    private final String townId;
    private final int index;
    private final Player attacker;
    private int amount;
    private boolean cancelled;

    public ControlPointDamageEvent(String structureId, String townId, int index, Player attacker, int amount) {
        this.structureId = structureId;
        this.townId = townId;
        this.index = index;
        this.attacker = attacker;
        this.amount = amount;
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

    /** The attacking player, or null (cannon, explosion). */
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
