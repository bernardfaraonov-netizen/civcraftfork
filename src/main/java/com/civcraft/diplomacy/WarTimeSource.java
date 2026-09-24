package com.civcraft.diplomacy;

/**
 * Optional integration for the war module: when a module implements this interface, its view of
 * "war is running now" (including admin-started wars) replaces the schedule-based one from config.yml.
 */
public interface WarTimeSource {

    boolean isWarTime();
}
