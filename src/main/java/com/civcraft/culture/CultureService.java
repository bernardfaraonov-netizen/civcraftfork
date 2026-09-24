package com.civcraft.culture;

import com.civcraft.balance.Balance;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.effect.StatService;
import com.civcraft.effect.Stats;
import com.civcraft.event.CultureChangedEvent;
import com.civcraft.model.Claim;
import com.civcraft.model.Town;
import com.civcraft.state.GameState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Computes which town's culture covers each chunk. A town covers every chunk whose centre lies within
 * its culture radius (by culture level) around the town centre; overlaps go to the town with more
 * accumulated culture. Claims in chunks that change hands move to the new town (CL 1.7.5).
 */
public final class CultureService {

    public record Level(int level, double required, int radius) {
    }

    private final GameState state;
    private final StatService stats;
    private final List<Level> levels = new ArrayList<>();
    private Map<ChunkKey, String> owners = new HashMap<>();
    private final Map<String, List<ChunkKey>> byTown = new HashMap<>();
    private boolean dirty = true;

    public CultureService(GameState state, StatService stats, Balance balance) {
        this.state = state;
        this.stats = stats;
        ConfigurationSection section = balance.section("core", "culture.levels");
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            levels.add(new Level(Integer.parseInt(key), s.getDouble("required"), s.getInt("radius")));
        }
        levels.sort((a, b) -> Integer.compare(a.level(), b.level()));
        if (levels.isEmpty()) levels.add(new Level(1, 0, 8));
    }

    public List<Level> levels() {
        return Collections.unmodifiableList(levels);
    }

    /** Culture required to reach {@code level}, including the town's requirement modifiers (min −50%). */
    public double required(Town town, int level) {
        Level l = levelDef(level);
        double percent = Math.max(-0.5, stats.town(town).percent(Stats.CULTURE_REQUIREMENT));
        return l.required() * (1 + percent);
    }

    public Level levelDef(int level) {
        for (Level l : levels) if (l.level() == level) return l;
        return levels.getLast();
    }

    public int maxLevel() {
        return levels.getLast().level();
    }

    /** Culture level from accumulated culture. */
    public int level(Town town) {
        int result = levels.getFirst().level();
        for (Level l : levels) {
            if (town.culture() >= required(town, l.level())) result = l.level();
        }
        return result;
    }

    public int radius(Town town) {
        return levelDef(level(town)).radius() + (int) stats.town(town).get("culture_radius");
    }

    public void invalidate() {
        dirty = true;
    }

    /** Town whose culture covers the chunk, or null. */
    public Town owner(ChunkKey chunk) {
        ensure();
        return state.town(owners.get(chunk));
    }

    public Collection<ChunkKey> chunks(Town town) {
        ensure();
        return byTown.getOrDefault(town.id(), List.of());
    }

    public boolean inCulture(Town town, ChunkKey chunk) {
        ensure();
        return town.id().equals(owners.get(chunk));
    }

    /** Whether any town of the given civ covers a chunk within {@code radius} chunks. */
    public Town nearestForeignCulture(String civId, ChunkKey chunk, int radius) {
        ensure();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                String id = owners.get(chunk.offset(dx, dz));
                if (id == null) continue;
                Town t = state.town(id);
                if (t != null && !Objects.equals(t.civId(), civId)) return t;
            }
        }
        return null;
    }

    /**
     * Culture chunks {@code town} would get if founded at {@code center} with the given level,
     * excluding chunks already cultured by others. Used by /town survey.
     */
    public List<ChunkKey> preview(BlockPos center, int level) {
        ensure();
        List<ChunkKey> result = new ArrayList<>();
        for (ChunkKey c : circle(center.chunk(), levelDef(level).radius())) {
            if (!owners.containsKey(c)) result.add(c);
        }
        return result;
    }

    private static List<ChunkKey> circle(ChunkKey center, int radius) {
        List<ChunkKey> result = new ArrayList<>();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) result.add(center.offset(dx, dz));
            }
        }
        return result;
    }

    private void ensure() {
        if (!dirty) return;
        dirty = false;
        recompute(false);
    }

    /** Recomputes the whole culture map now and fires {@link CultureChangedEvent} for changes. */
    public void recompute(boolean fireEvents) {
        Tasks.checkMain();
        Map<ChunkKey, String> next = new HashMap<>();
        Map<ChunkKey, Double> strength = new HashMap<>();
        for (Town town : state.towns()) {
            if (town.center() == null) continue;
            for (ChunkKey c : circle(town.center().chunk(), radius(town))) {
                Double current = strength.get(c);
                if (current == null || town.culture() > current) {
                    strength.put(c, town.culture());
                    next.put(c, town.id());
                }
            }
        }
        Map<ChunkKey, String> changes = new HashMap<>();
        for (Map.Entry<ChunkKey, String> e : next.entrySet()) {
            if (!e.getValue().equals(owners.get(e.getKey()))) changes.put(e.getKey(), e.getValue());
        }
        for (ChunkKey old : owners.keySet()) {
            if (!next.containsKey(old)) changes.put(old, null);
        }
        owners = next;
        byTown.clear();
        for (Map.Entry<ChunkKey, String> e : owners.entrySet()) {
            byTown.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        // Claims follow the culture that swallowed them.
        for (Map.Entry<ChunkKey, String> change : changes.entrySet()) {
            Claim claim = state.claim(change.getKey());
            if (claim == null || change.getValue() == null || claim.townId().equals(change.getValue())) continue;
            if (claim.locked()) continue;
            claim.townId(change.getValue());
            claim.owner(null);
            claim.price(0);
            state.save(claim);
        }
        if (fireEvents && !changes.isEmpty()) new CultureChangedEvent(changes).call();
    }
}
