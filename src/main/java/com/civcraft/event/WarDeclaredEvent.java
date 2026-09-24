package com.civcraft.event;

/** A civilization declared war on another one. */
public final class WarDeclaredEvent extends CivEvent {

    private final String aggressorCivId;
    private final String defenderCivId;

    public WarDeclaredEvent(String aggressorCivId, String defenderCivId) {
        this.aggressorCivId = aggressorCivId;
        this.defenderCivId = defenderCivId;
    }

    public String aggressorCivId() {
        return aggressorCivId;
    }

    public String defenderCivId() {
        return defenderCivId;
    }
}
