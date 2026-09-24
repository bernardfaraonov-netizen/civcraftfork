package com.civcraft.economy;

import com.civcraft.model.Town;
import java.util.Map;

/**
 * Optional integration for the structure module: the daily "tax per day" of every structure of a
 * town (spec §8.3 buildingUpkeep), in hundredths, keyed by a display label. The economy applies the
 * government, war and town-count multipliers on top. Every module implementing it contributes.
 */
public interface StructureUpkeepSource {

    Map<String, Long> structureUpkeep(Town town);
}
