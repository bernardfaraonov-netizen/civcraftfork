package com.civcraft.event;

/** Hourly science produced by a town (own beakers plus converted taxes); consumed by research. */
public final class BeakersProducedEvent extends CivEvent {

    private final String civId;
    private final String townId;
    private final double beakers;

    public BeakersProducedEvent(String civId, String townId, double beakers) {
        this.civId = civId;
        this.townId = townId;
        this.beakers = beakers;
    }

    public String civId() {
        return civId;
    }

    public String townId() {
        return townId;
    }

    public double beakers() {
        return beakers;
    }
}
