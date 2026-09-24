package com.civcraft.civ;

import com.civcraft.storage.Stored;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Civ-module state kept beside the core {@code Civilization} document. */
public final class CivData implements Stored {

    public static final String COLLECTION = "civ_data";

    private String civId;
    private List<String> motd = new ArrayList<>();
    /** Newest last: "epochSeconds|action|player|town". */
    private List<String> joinLog = new ArrayList<>();
    private Set<UUID> mutedPlayers = new HashSet<>();
    private Set<String> mutedTowns = new HashSet<>();
    private int scoutSeconds = 60;
    private int scoutRate = 60;

    private CivData() {
    }

    public CivData(String civId) {
        this.civId = civId;
    }

    @Override
    public String storageId() {
        return civId;
    }

    public String civId() {
        return civId;
    }

    public List<String> motd() {
        return motd;
    }

    public List<String> joinLog() {
        return joinLog;
    }

    public Set<UUID> mutedPlayers() {
        return mutedPlayers;
    }

    public Set<String> mutedTowns() {
        return mutedTowns;
    }

    public int scoutSeconds() {
        return scoutSeconds;
    }

    public void scoutSeconds(int s) {
        this.scoutSeconds = s;
    }

    public int scoutRate() {
        return scoutRate;
    }

    public void scoutRate(int r) {
        this.scoutRate = r;
    }
}
