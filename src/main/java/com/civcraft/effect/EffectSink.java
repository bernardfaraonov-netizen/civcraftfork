package com.civcraft.effect;

import com.civcraft.model.Civilization;
import com.civcraft.model.Town;

/** Receives modifiers from {@link EffectProvider}s and routes them by {@link Scope}. */
public interface EffectSink {

    /** A modifier whose source lives in {@code sourceTown}. */
    void town(Town sourceTown, Modifier modifier);

    /** A modifier whose source is the civilization itself (tech, government, talent...). */
    void civ(Civilization civ, Modifier modifier);
}
