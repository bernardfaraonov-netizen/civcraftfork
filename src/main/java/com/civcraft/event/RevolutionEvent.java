package com.civcraft.event;

/** A conquered civilization started a revolution: its towns returned; research/talent modules react (lose last techs, restore talents). */
public final class RevolutionEvent extends CivEvent {

    private final String civId;

    public RevolutionEvent(String civId) {
        this.civId = civId;
    }

    public String civId() {
        return civId;
    }
}
