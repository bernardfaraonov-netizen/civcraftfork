package com.civcraft.space;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Space program of one civilization (collection {@code space}). */
public final class SpaceState implements Stored {

    private String civId;
    private int completed;
    /** Mission being executed (1-based), 0 when idle. */
    private int current;
    private double hammers;
    private double beakers;
    /** Missions whose rewards were already paid (repeated missions give nothing). */
    private Set<Integer> rewarded = new HashSet<>();
    private boolean wasConquered;
    private Instant launchedAt;

    private SpaceState() {
    }

    public SpaceState(String civId) {
        this.civId = civId;
    }

    @Override
    public String storageId() {
        return civId;
    }

    void repair() {
        if (rewarded == null) rewarded = new HashSet<>();
        if (!Double.isFinite(hammers) || hammers < 0) hammers = 0;
        if (!Double.isFinite(beakers) || beakers < 0) beakers = 0;
    }

    public String civId() {
        return civId;
    }

    public int completed() {
        return completed;
    }

    public void completed(int n) {
        this.completed = Math.max(0, n);
    }

    public int current() {
        return current;
    }

    public void launch(int mission) {
        this.current = mission;
        this.hammers = 0;
        this.beakers = 0;
        this.launchedAt = mission == 0 ? null : Instant.now();
    }

    public double hammers() {
        return hammers;
    }

    public void addHammers(double v) {
        this.hammers += v;
    }

    public double beakers() {
        return beakers;
    }

    public void addBeakers(double v) {
        this.beakers += v;
    }

    public Set<Integer> rewarded() {
        return rewarded;
    }

    public boolean wasConquered() {
        return wasConquered;
    }

    public void wasConquered(boolean v) {
        this.wasConquered = v;
    }

    public Instant launchedAt() {
        return launchedAt;
    }
}
