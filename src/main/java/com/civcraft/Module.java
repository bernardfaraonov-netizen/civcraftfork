package com.civcraft;

/**
 * A self-contained game subsystem (camps, structures, research, war...). Modules are enabled in
 * registration order after the core state is loaded and disabled in reverse order.
 */
public interface Module {

    /** Short id, also the name of the module's language and balance files. */
    String id();

    /** Load balance data and persisted documents. The server has not started ticking yet. */
    default void load(CivCraft civ) {
    }

    /** Register listeners, commands, clock jobs and effect providers. */
    default void enable(CivCraft civ) {
    }

    /** Stop tasks and write pending state synchronously. */
    default void disable(CivCraft civ) {
    }
}
