package com.civcraft.effect;

/**
 * How a modifier combines. The final value of a stat is
 * {@code (base + ΣADD) × (1 + ΣPERCENT) × ΠMULTIPLY}, then clamped by MIN/MAX modifiers.
 */
public enum Op {
    ADD,
    PERCENT,
    MULTIPLY,
    /** Overrides the final value with at least this. */
    MIN,
    /** Overrides the final value with at most this. */
    MAX
}
