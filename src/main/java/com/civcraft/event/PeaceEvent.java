package com.civcraft.event;

/** A war between two civilizations ended (peace/neutral agreement, expiry or admin). */
public final class PeaceEvent extends CivEvent {

    private final String civA;
    private final String civB;

    public PeaceEvent(String civA, String civB) {
        this.civA = civA;
        this.civB = civB;
    }

    public String civA() {
        return civA;
    }

    public String civB() {
        return civB;
    }
}
