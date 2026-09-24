package com.civcraft.structure;

import com.civcraft.core.util.Cuboid;
import com.civcraft.model.Town;
import com.civcraft.structure.type.StructureType;
import java.util.Optional;

/**
 * Site check contributed by another module (camps, trade resource points, war rules). Returns a message key of the
 * reason when the structure may not be built on that volume.
 */
@FunctionalInterface
public interface FootprintGate {

    Optional<String> check(Town town, StructureType type, Cuboid volume);
}
