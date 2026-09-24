package com.civcraft.religion;

import com.civcraft.storage.Stored;

/** Global religion bookkeeping (collection {@code religion_meta}, single document). */
public final class ReligionMeta implements Stored {

    /** Whether the war window was active at the last check. */
    private boolean war;
    /** A daily re-sort was skipped during war and must run when it ends. */
    private boolean pendingResort;

    @Override
    public String storageId() {
        return "meta";
    }

    public boolean war() {
        return war;
    }

    public void war(boolean war) {
        this.war = war;
    }

    public boolean pendingResort() {
        return pendingResort;
    }

    public void pendingResort(boolean v) {
        this.pendingResort = v;
    }
}
