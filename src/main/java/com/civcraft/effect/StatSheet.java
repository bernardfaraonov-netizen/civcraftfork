package com.civcraft.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates modifiers for one target (a town) and computes final stat values. Rebuilt whenever
 * something that provides effects changes; reads are cheap.
 */
public final class StatSheet {

    private final Map<String, List<Modifier>> modifiers = new HashMap<>();
    private final Map<String, Double> cache = new HashMap<>();

    public void add(Modifier modifier) {
        modifiers.computeIfAbsent(modifier.stat(), k -> new ArrayList<>()).add(modifier);
        cache.remove(modifier.stat());
    }

    public void addAll(Iterable<Modifier> list) {
        list.forEach(this::add);
    }

    public void clear() {
        modifiers.clear();
        cache.clear();
    }

    /** Final value applying the stat's modifiers to {@code base}. */
    public double apply(String stat, double base) {
        List<Modifier> list = modifiers.get(stat);
        if (list == null) return base;
        double add = 0;
        double percent = 0;
        double multiply = 1;
        double min = Double.NEGATIVE_INFINITY;
        double max = Double.POSITIVE_INFINITY;
        for (Modifier m : list) {
            switch (m.op()) {
                case ADD -> add += m.value();
                case PERCENT -> percent += m.value();
                case MULTIPLY -> multiply *= m.value();
                case MIN -> min = Math.max(min, m.value());
                case MAX -> max = Math.min(max, m.value());
            }
        }
        double value = (base + add) * (1 + percent) * multiply;
        return Math.max(min, Math.min(max, value));
    }

    /** Final value with a base of zero (pure sum of modifiers). Cached. */
    public double get(String stat) {
        return cache.computeIfAbsent(stat, s -> apply(s, 0));
    }

    /** Sum of the PERCENT modifiers only, e.g. "+25% culture". */
    public double percent(String stat) {
        return modifiers.getOrDefault(stat, List.of()).stream()
                .filter(m -> m.op() == Op.PERCENT).mapToDouble(Modifier::value).sum();
    }

    public boolean has(String stat) {
        return modifiers.containsKey(stat) && !modifiers.get(stat).isEmpty();
    }

    public List<Modifier> breakdown(String stat) {
        return List.copyOf(modifiers.getOrDefault(stat, List.of()));
    }

    public Map<String, List<Modifier>> all() {
        return modifiers;
    }
}
