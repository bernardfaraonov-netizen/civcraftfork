package com.civcraft.effect;

import com.civcraft.core.task.Tasks;
import com.civcraft.model.Civilization;
import com.civcraft.model.Town;
import com.civcraft.state.GameState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the {@link StatSheet} of every town and civilization. Sheets are rebuilt lazily: anything
 * that changes effects calls {@link #invalidate()}, and the next read rebuilds all sheets once.
 */
public final class StatService {

    private final GameState state;
    private final Logger logger;
    private final List<EffectProvider> providers = new ArrayList<>();
    private final Map<String, StatSheet> townSheets = new HashMap<>();
    private final Map<String, StatSheet> civSheets = new HashMap<>();
    private boolean dirty = true;
    private boolean rebuilding;

    public StatService(GameState state, Logger logger) {
        this.state = state;
        this.logger = logger;
    }

    public void register(EffectProvider provider) {
        providers.add(provider);
        dirty = true;
    }

    public void invalidate() {
        dirty = true;
    }

    public StatSheet town(Town town) {
        ensure();
        return townSheets.computeIfAbsent(town.id(), k -> new StatSheet());
    }

    public StatSheet civ(Civilization civ) {
        ensure();
        return civSheets.computeIfAbsent(civ.id(), k -> new StatSheet());
    }

    /** Final value of a town stat applied to a base. */
    public double town(Town town, String stat, double base) {
        return town(town).apply(stat, base);
    }

    public double civ(Civilization civ, String stat, double base) {
        return civ(civ).apply(stat, base);
    }

    private void ensure() {
        if (!dirty || rebuilding) return;
        Tasks.checkMain();
        rebuilding = true;
        try {
            rebuild();
        } finally {
            rebuilding = false;
            dirty = false;
        }
    }

    private void rebuild() {
        townSheets.clear();
        civSheets.clear();
        for (Town t : state.towns()) townSheets.put(t.id(), new StatSheet());
        for (Civilization c : state.civs()) civSheets.put(c.id(), new StatSheet());
        EffectSink sink = new EffectSink() {
            @Override
            public void town(Town source, Modifier m) {
                switch (m.scope()) {
                    case TOWN -> add(source, m);
                    case CIV -> {
                        Civilization civ = state.civOf(source);
                        if (civ != null) civ(civ, m);
                        else add(source, m);
                    }
                    case CAPITAL -> {
                        Civilization civ = state.civOf(source);
                        Town capital = civ == null ? null : state.capital(civ);
                        if (capital != null) add(capital, m);
                    }
                    case GLOBAL -> state.towns().forEach(t -> add(t, m));
                }
            }

            @Override
            public void civ(Civilization civ, Modifier m) {
                switch (m.scope()) {
                    case TOWN, CIV -> {
                        civSheets.computeIfAbsent(civ.id(), k -> new StatSheet()).add(m);
                        for (Town t : state.towns(civ)) add(t, m);
                    }
                    case CAPITAL -> {
                        Town capital = state.capital(civ);
                        if (capital != null) add(capital, m);
                    }
                    case GLOBAL -> {
                        civSheets.values().forEach(s -> s.add(m));
                        state.towns().forEach(t -> add(t, m));
                    }
                }
            }

            private void add(Town t, Modifier m) {
                townSheets.computeIfAbsent(t.id(), k -> new StatSheet()).add(m);
            }
        };
        for (EffectProvider provider : providers) {
            try {
                provider.contribute(sink);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Effect provider failed: " + provider, e);
            }
        }
    }
}
