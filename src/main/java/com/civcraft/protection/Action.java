package com.civcraft.protection;

/** Kinds of world interaction checked by {@link ProtectionService}. */
public enum Action {
    /** Breaking a block (players, or environment when actor is null: explosions, pistons...). */
    BREAK,
    /** Placing a block. */
    PLACE,
    /** Using doors, chests, buttons, levers, plates, containers. */
    INTERACT,
    /** Using buckets, flint and steel, spawn eggs, bone meal and similar items on a block. */
    ITEMUSE,
    /** Damaging or using protected entities (item frames, armor stands, animals in plots). */
    ENTITY,
    /** Fire spreading or burning a block. */
    FIRE,
    /** Liquids flowing, pistons, dispensers: environment moving blocks across a boundary. */
    FLOW
}
