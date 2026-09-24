package com.civcraft.model;

public enum TownStatus {
    /** Founded in its current civilization. */
    NATIVE,
    /** Captured and then capitulated (or auto-capitulated after 7 days). */
    AFFILIATED,
    /** Captured during a war, not yet capitulated. */
    CAPTURED,
    /** Bought on the market. Does not count for victories. */
    BOUGHT
}
