package com.civcraft.structure.type;

/** Upgrade level of a structure type: price (hundredths), hammers and required technology (may be null). */
public record LevelDef(int level, long cost, double hammers, String tech) {
}
