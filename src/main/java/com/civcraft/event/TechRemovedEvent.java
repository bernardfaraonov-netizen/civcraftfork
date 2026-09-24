package com.civcraft.event;

/** A civilization lost a researched technology (revolution, admin command). */
public final class TechRemovedEvent extends CivEvent {

    private final String civId;
    private final String techId;

    public TechRemovedEvent(String civId, String techId) {
        this.civId = civId;
        this.techId = techId;
    }

    public String civId() {
        return civId;
    }

    public String techId() {
        return techId;
    }
}
