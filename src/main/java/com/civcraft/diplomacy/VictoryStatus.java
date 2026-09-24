package com.civcraft.diplomacy;

/**
 * Optional integration for the victory module. Several deadlines (war declarations 24 h instead of
 * 72 h before the war, gifts 1 day instead of 3, /t add, /market) are relaxed while a victory
 * countdown runs. Without an implementing module no victory is considered running.
 */
public interface VictoryStatus {

    boolean victoryCountdownRunning();

    /** Days left until the leading victory completes, or -1 when none runs. */
    default int daysUntilVictory() {
        return -1;
    }
}
