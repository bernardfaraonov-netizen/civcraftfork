package com.civcraft.effect;

/**
 * One contribution to a stat. {@code source} is a human readable id ("structure:library",
 * "tech:writing", "government:monarchy") used by the breakdown in {@code /town info}.
 */
public record Modifier(String stat, Op op, double value, Scope scope, String source) {

    public Modifier withSource(String newSource) {
        return new Modifier(stat, op, value, scope, newSource);
    }

    public Modifier scaled(double factor) {
        return op == Op.MULTIPLY || op == Op.MIN || op == Op.MAX ? this : new Modifier(stat, op, value * factor, scope, source);
    }
}
