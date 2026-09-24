package com.civcraft.effect;

/** Something that contributes modifiers (buildings, techs, governments, trade goods...). */
@FunctionalInterface
public interface EffectProvider {

    void contribute(EffectSink sink);
}
