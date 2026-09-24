package com.civcraft.event;

/** A civilization completed a space mission (spec 03 §9). {@code rewarded} is false for repeated missions. */
public final class SpaceMissionCompletedEvent extends CivEvent {

    private final String civId;
    private final int mission;
    private final boolean rewarded;

    public SpaceMissionCompletedEvent(String civId, int mission, boolean rewarded) {
        this.civId = civId;
        this.mission = mission;
        this.rewarded = rewarded;
    }

    public String civId() {
        return civId;
    }

    public int mission() {
        return mission;
    }

    public boolean rewarded() {
        return rewarded;
    }
}
