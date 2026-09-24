package com.civcraft.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Key/value document collections on top of SQL. Each collection is one table
 * {@code (id, data, updated_at)}. The whole game state is loaded into memory at startup, so documents
 * never need to be queried by content.
 */
public final class DocumentStore {

    private final Database db;
    private final Logger logger;

    public DocumentStore(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void createCollection(String collection) {
        String type = db.dialect() == Database.Dialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        db.execute("CREATE TABLE IF NOT EXISTS " + db.table(collection)
                + " (id VARCHAR(128) NOT NULL PRIMARY KEY, data " + type + " NOT NULL, updated_at BIGINT NOT NULL)");
    }

    /** Loads every document in the collection. Malformed documents are logged and skipped. */
    public <T> List<T> loadAll(String collection, Class<T> type) {
        List<T> result = new ArrayList<>();
        db.blocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, data FROM " + db.table(collection));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    try {
                        result.add(Json.GSON.fromJson(rs.getString(2), type));
                    } catch (RuntimeException e) {
                        logger.severe("Skipping corrupt " + collection + " document " + id + ": " + e.getMessage());
                    }
                }
            }
        });
        return result;
    }

    /**
     * Writes pre-serialized documents and deletes the given ids in one transaction. Serialization must
     * happen on the main thread (see {@link SaveQueue}); this only performs IO.
     */
    public CompletableFuture<Void> apply(String collection, Map<String, String> upserts, List<String> deletes) {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String table = db.table(collection);
        String upsert = db.dialect() == Database.Dialect.MYSQL
                ? "INSERT INTO " + table + " (id, data, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data), updated_at = VALUES(updated_at)"
                : "INSERT INTO " + table + " (id, data, updated_at) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at";
        long now = System.currentTimeMillis();
        return db.write(c -> {
            if (!upserts.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    for (Map.Entry<String, String> e : upserts.entrySet()) {
                        ps.setString(1, e.getKey());
                        ps.setString(2, e.getValue());
                        ps.setLong(3, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!deletes.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                    for (String id : deletes) {
                        ps.setString(1, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        });
    }
}
