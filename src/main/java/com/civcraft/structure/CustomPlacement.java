package com.civcraft.structure;

import com.civcraft.core.CivException;
import com.civcraft.model.Town;
import com.civcraft.structure.type.StructureType;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * Construction flow for structures without a template (walls and roads: two markers, spec 02 §5.8–5.9). Registered
 * per category or type with {@link StructureModule#registerCustomPlacement(String, CustomPlacement)}; it validates,
 * charges and finally calls {@link StructureModule#placeCustom}.
 */
@FunctionalInterface
public interface CustomPlacement {

    void begin(Player player, Town town, StructureType type, List<String> args) throws CivException;
}
