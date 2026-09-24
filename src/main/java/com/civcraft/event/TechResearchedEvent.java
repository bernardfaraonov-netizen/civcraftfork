package com.civcraft.event;

/** A civilization finished researching a technology (fired after the tech was added). */
public final class TechResearchedEvent extends CivEvent {

    private final String civId;
    private final String techId;

    public TechResearchedEvent(String civId, String techId) {
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
