package com.civcraft.storage;

import com.civcraft.core.task.Tasks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Dirty tracking with write-behind. Game code calls {@link #save} / {@link #delete} freely on the main
 * thread; every flush serializes the dirty objects on the main thread (so the JSON is a consistent
 * snapshot) and hands only strings to the database writer thread.
 */
public final class SaveQueue {

    private final DocumentStore store;
    private final Map<String, Map<String, Stored>> dirty = new LinkedHashMap<>();
    private final Map<String, Set<String>> deleted = new HashMap<>();

    public SaveQueue(DocumentStore store) {
        this.store = store;
    }

    public void save(String collection, Stored object) {
        Tasks.checkMain();
        dirty.computeIfAbsent(collection, k -> new LinkedHashMap<>()).put(object.storageId(), object);
        Set<String> gone = deleted.get(collection);
        if (gone != null) gone.remove(object.storageId());
    }

    public void delete(String collection, String id) {
        Tasks.checkMain();
        Map<String, Stored> pending = dirty.get(collection);
        if (pending != null) pending.remove(id);
        deleted.computeIfAbsent(collection, k -> new LinkedHashSet<>()).add(id);
    }

    public boolean isEmpty() {
        return dirty.values().stream().allMatch(Map::isEmpty) && deleted.values().stream().allMatch(Set::isEmpty);
    }

    /** Serializes all pending changes and schedules the writes. Must run on the main thread. */
    public CompletableFuture<Void> flush() {
        Tasks.checkMain();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Set<String> collections = new LinkedHashSet<>(dirty.keySet());
        collections.addAll(deleted.keySet());
        for (String collection : collections) {
            Map<String, String> json = new LinkedHashMap<>();
            Map<String, Stored> objects = dirty.getOrDefault(collection, Map.of());
            for (Map.Entry<String, Stored> e : objects.entrySet()) {
                json.put(e.getKey(), Json.GSON.toJson(e.getValue()));
            }
            List<String> deletes = new ArrayList<>(deleted.getOrDefault(collection, Set.of()));
            futures.add(store.apply(collection, json, deletes));
        }
        dirty.clear();
        deleted.clear();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
}
