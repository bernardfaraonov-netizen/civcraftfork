package com.civcraft.resident;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player state of the resident module that is not part of the core {@code Resident}. */
public final class ResidentData implements Stored {

    public static final String COLLECTION = "resident_data";

    private UUID uuid;
    /** Civilizations and towns whose members are all friends (/res friends add civ|town). */
    private Set<String> friendCivs = new HashSet<>();
    private Set<String> friendTowns = new HashSet<>();
    private boolean kitGiven;
    /** Cooldown key ("town-teleport", "camp-teleport"...) → last use. */
    private Map<String, Instant> cooldowns = new HashMap<>();
    /** Remaining paid /rename uses (spec §6.8). */
    private int renames;

    private ResidentData() {
    }

    public ResidentData(UUID uuid) {
        this.uuid = uuid;
    }

    @Override
    public String storageId() {
        return uuid.toString();
    }

    public UUID uuid() {
        return uuid;
    }

    public Set<String> friendCivs() {
        return friendCivs;
    }

    public Set<String> friendTowns() {
        return friendTowns;
    }

    public boolean kitGiven() {
        return kitGiven;
    }

    public void kitGiven(boolean given) {
        this.kitGiven = given;
    }

    public Instant cooldown(String key) {
        return cooldowns.get(key);
    }

    public void cooldown(String key, Instant at) {
        if (at == null) cooldowns.remove(key);
        else cooldowns.put(key, at);
    }

    public int renames() {
        return renames;
    }

    public void renames(int renames) {
        this.renames = renames;
    }
}
