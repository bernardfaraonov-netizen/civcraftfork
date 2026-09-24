package com.civcraft.event;

import java.util.List;

/**
 * A victory was achieved and the phase is over (spec 03 §11). With {@code draw} several civilizations
 * finished their countdowns at the same moment and all of them won.
 */
public final class VictoryEvent extends CivEvent {

    private final List<String> civIds;
    private final String type;
    private final boolean draw;

    public VictoryEvent(List<String> civIds, String type, boolean draw) {
        this.civIds = List.copyOf(civIds);
        this.type = type;
        this.draw = draw;
    }

    public List<String> civIds() {
        return civIds;
    }

    /** Victory type id: religious, economic, diplomatic, cultural, scientific, domination. */
    public String type() {
        return type;
    }

    public boolean draw() {
        return draw;
    }
}
