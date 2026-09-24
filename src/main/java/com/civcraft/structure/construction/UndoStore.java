package com.civcraft.structure.construction;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Files {@code plugins/CivCraft/undo/<structure id>.bin} holding the terrain replaced by each structure. Writes
 * happen on one IO thread from immutable snapshots taken on the main thread; the final flush on shutdown is
 * synchronous.
 */
public final class UndoStore {

    private final File dir;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CivCraft-undo-io");
        t.setDaemon(true);
        return t;
    });

    public UndoStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "undo");
        this.logger = logger;
    }

    public File file(String structureId) {
        return new File(dir, structureId.replaceAll("[^A-Za-z0-9_-]", "_") + ".bin");
    }

    public boolean exists(String structureId) {
        return file(structureId).isFile();
    }

    /** Asynchronously writes a snapshot of the buffer. Call on the main thread. */
    public void saveAsync(String structureId, UndoBuffer buffer) {
        UndoBuffer snapshot = buffer.snapshot();
        buffer.clean();
        File target = file(structureId);
        io.execute(() -> {
            try {
                snapshot.write(target);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Cannot write undo data " + target, e);
            }
        });
    }

    /** Synchronous write (plugin disable). */
    public void saveNow(String structureId, UndoBuffer buffer) {
        try {
            buffer.write(file(structureId));
            buffer.clean();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Cannot write undo data for " + structureId, e);
        }
    }

    /** Reads synchronously (startup only); null if missing or corrupt. */
    public UndoBuffer loadNow(String structureId) {
        File f = file(structureId);
        if (!f.isFile()) return null;
        try {
            return UndoBuffer.read(f);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Corrupt undo data " + f + "; terrain cannot be restored", e);
            return null;
        }
    }

    /** Reads on the IO thread (after pending writes of the same file). */
    public CompletableFuture<UndoBuffer> loadAsync(String structureId) {
        File f = file(structureId);
        return CompletableFuture.supplyAsync(() -> {
            if (!f.isFile()) return null;
            try {
                return UndoBuffer.read(f);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Corrupt undo data " + f, e);
                return null;
            }
        }, io);
    }

    public void delete(String structureId) {
        File f = file(structureId);
        io.execute(() -> {
            if (f.isFile() && !f.delete()) logger.warning("Cannot delete " + f);
        });
    }

    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) logger.warning("Undo IO did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
