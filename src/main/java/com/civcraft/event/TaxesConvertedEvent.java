package com.civcraft.event;

/**
 * Fired by the town/economy module when part of a town's civ tax was converted into beakers
 * (spec 01 §8.2). The science module records it for {@code /civ research taxes}; the beakers themselves
 * still arrive through {@link BeakersProducedEvent}.
 *
 * @param coinsCents coins spent on beakers, in hundredths
 */
public final class TaxesConvertedEvent extends CivEvent {

    private final String civId;
    private final String townId;
    private final long coinsCents;
    private final double beakers;

    public TaxesConvertedEvent(String civId, String townId, long coinsCents, double beakers) {
        this.civId = civId;
        this.townId = townId;
        this.coinsCents = coinsCents;
        this.beakers = beakers;
    }

    public String civId() {
        return civId;
    }

    public String townId() {
        return townId;
    }

    public long coinsCents() {
        return coinsCents;
    }

    public double beakers() {
        return beakers;
    }
}
