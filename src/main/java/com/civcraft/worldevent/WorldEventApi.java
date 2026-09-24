package com.civcraft.worldevent;

import java.time.Instant;
import net.kyori.adventure.audience.Audience;

/**
 * Weekly world events (spec 04 §13). Town and civ stat effects are delivered through the
 * {@code StatService}; values that are not town stats (rat strength, mercury chance...) are read
 * with {@link #global(String)}.
 */
public interface WorldEventApi {

    /** Id of the running event (e.g. {@code fat_rats}), or null. */
    String activeEvent();

    /** When the running event ends, or null. */
    Instant activeUntil();

    /** Start of the next scheduled weekly draw. */
    Instant nextDraw();

    /**
     * Sum of the running event's global value for the key (0 when none). Keys used by CivCraft:
     * {@code mob.rat.health_percent}, {@code mob.rat.damage_add}, {@code mob.rat.mercury_multiplier}.
     */
    double global(String key);

    /** Prints the current event (name, description, remaining time) — used by {@code /civ event}. */
    void describe(Audience audience);
}
