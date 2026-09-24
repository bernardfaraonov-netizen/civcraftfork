package com.civcraft.effect;

/** Who an effect applies to, relative to the object that provides it. */
public enum Scope {
    /** Only the town that owns the source (a building in that town, a trade good bound to it...). */
    TOWN,
    /** Every town of the source's civilization. */
    CIV,
    /** Only the capital of the source's civilization. */
    CAPITAL,
    /** Every town of every civilization on the server (world wonders with global effects). */
    GLOBAL
}
