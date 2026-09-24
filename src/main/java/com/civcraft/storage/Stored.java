package com.civcraft.storage;

/** A domain object persisted as a JSON document in its own collection. */
public interface Stored {

    /** Stable primary key, unique within the collection. */
    String storageId();
}
