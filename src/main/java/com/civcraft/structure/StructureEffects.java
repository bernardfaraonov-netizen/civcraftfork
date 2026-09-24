package com.civcraft.structure;

import com.civcraft.CivCraft;
import com.civcraft.effect.EffectProvider;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.model.Town;

/**
 * Static effects of every working structure (balance {@code effects:}) plus dynamic contributions of behaviours.
 * Keyed by the live index, so removed or destroyed structures stop contributing immediately (audit B #24, #25).
 */
final class StructureEffects implements EffectProvider {

    private final StructureModule module;

    StructureEffects(StructureModule module, CivCraft civ) {
        this.module = module;
    }

    @Override
    public void contribute(EffectSink sink) {
        for (Structure s : module.index().all()) {
            if (!s.isActive()) continue;
            Town town = module.town(s);
            if (town == null) continue;
            if (s.typeDef().requiresActive() && !module.requirementsHold(s, town)) continue;
            for (Modifier m : s.typeDef().effects()) sink.town(town, m);
            module.safe(s, "contributeEffects", () -> module.behavior(s.type()).contributeEffects(s, sink));
        }
    }

    @Override
    public String toString() {
        return "StructureEffects";
    }
}
