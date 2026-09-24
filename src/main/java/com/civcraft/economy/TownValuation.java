package com.civcraft.economy;

import com.civcraft.model.Town;

/**
 * Optional integration: total build cost (hundredths) of a town's structures, used for the market
 * price (spec §8.7: debt + 50 % of all structures) and scores. Every implementing module contributes.
 */
public interface TownValuation {

    long structuresValue(Town town);

    /** Score points of the town's structures for /t top5 and /c top5. */
    default double structuresScore(Town town) {
        return 0;
    }
}
