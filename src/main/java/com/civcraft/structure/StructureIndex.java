package com.civcraft.structure;

import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.structure.component.StructureComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory lookup of structures by id, town, chunk and component position. Registration and removal are the only
 * way protection is granted or revoked, so there is no "phantom" protection after removal.
 */
final class StructureIndex {

    private final Map<String, Structure> byId = new LinkedHashMap<>();
    private final Map<String, List<Structure>> byTown = new HashMap<>();
    private final Map<ChunkKey, List<Structure>> byChunk = new HashMap<>();
    private final Map<BlockPos, StructureComponent> components = new HashMap<>();

    void add(Structure s) {
        byId.put(s.id(), s);
        byTown.computeIfAbsent(s.townId(), k -> new ArrayList<>()).add(s);
        for (ChunkKey c : s.chunks()) byChunk.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
        indexComponents(s);
    }

    void remove(Structure s) {
        byId.remove(s.id());
        List<Structure> town = byTown.get(s.townId());
        if (town != null) {
            town.remove(s);
            if (town.isEmpty()) byTown.remove(s.townId());
        }
        for (ChunkKey c : s.chunks()) {
            List<Structure> list = byChunk.get(c);
            if (list == null) continue;
            list.remove(s);
            if (list.isEmpty()) byChunk.remove(c);
        }
        for (StructureComponent c : s.components()) components.remove(c.pos(), c);
    }

    void indexComponents(Structure s) {
        for (StructureComponent c : s.components()) components.put(c.pos(), c);
    }

    Structure byId(String id) {
        return byId.get(id);
    }

    Collection<Structure> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    List<Structure> town(String townId) {
        List<Structure> list = byTown.get(townId);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    List<Structure> chunk(ChunkKey key) {
        List<Structure> list = byChunk.get(key);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    Structure at(BlockPos pos) {
        List<Structure> list = byChunk.get(pos.chunk());
        if (list == null) return null;
        for (Structure s : list) if (s.contains(pos)) return s;
        return null;
    }

    StructureComponent component(BlockPos pos) {
        return components.get(pos);
    }
}
