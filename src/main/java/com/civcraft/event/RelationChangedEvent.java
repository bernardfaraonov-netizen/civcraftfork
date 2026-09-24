package com.civcraft.event;

import com.civcraft.model.RelationType;

/** The diplomatic relation between two civilizations changed. */
public final class RelationChangedEvent extends CivEvent {

    private final String civA;
    private final String civB;
    private final RelationType from;
    private final RelationType to;

    public RelationChangedEvent(String civA, String civB, RelationType from, RelationType to) {
        this.civA = civA;
        this.civB = civB;
        this.from = from;
        this.to = to;
    }

    public String civA() {
        return civA;
    }

    public String civB() {
        return civB;
    }

    public RelationType from() {
        return from;
    }

    public RelationType to() {
        return to;
    }
}
