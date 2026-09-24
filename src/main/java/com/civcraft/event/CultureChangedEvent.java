package com.civcraft.event;

import com.civcraft.core.util.ChunkKey;
import java.util.Map;

/**
 * Fired after the culture map was recomputed. {@code changes} maps every chunk whose owner changed to
 * its new town id (null = no longer cultured).
 */
public final class CultureChangedEvent extends CivEvent {

    private final Map<ChunkKey, String> changes;

    public CultureChangedEvent(Map<ChunkKey, String> changes) {
        this.changes = changes;
    }

    public Map<ChunkKey, String> changes() {
        return changes;
    }
}
