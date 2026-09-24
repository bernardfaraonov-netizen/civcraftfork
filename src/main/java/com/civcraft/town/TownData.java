package com.civcraft.town;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Town-module state kept beside the core {@code Town} document. */
public final class TownData implements Stored {

    public static final String COLLECTION = "town_data";

    /** An upgrade that has been paid for and is collecting hammers. */
    public static final class Job {
        private String upgrade;
        private double progress;
        private double required;
        private long paid;
        private Instant started;

        private Job() {
        }

        public Job(String upgrade, double required, long paid) {
            this.upgrade = upgrade;
            this.required = required;
            this.paid = paid;
            this.started = Instant.now();
        }

        public String upgrade() {
            return upgrade;
        }

        public double progress() {
            return progress;
        }

        public void progress(double progress) {
            this.progress = progress;
        }

        public double required() {
            return required;
        }

        public long paid() {
            return paid;
        }

        public Instant started() {
            return started;
        }
    }

    private String townId;
    private Set<String> upgrades = new HashSet<>();
    private List<Job> jobs = new ArrayList<>();
    private String motd;
    private int scoutRate = 60;
    private Instant lastBorderLevel;
    private int lastCultureLevel = 1;

    private TownData() {
    }

    public TownData(String townId) {
        this.townId = townId;
    }

    @Override
    public String storageId() {
        return townId;
    }

    public String townId() {
        return townId;
    }

    public Set<String> upgrades() {
        return upgrades;
    }

    public List<Job> jobs() {
        return jobs;
    }

    public String motd() {
        return motd;
    }

    public void motd(String motd) {
        this.motd = motd;
    }

    public int scoutRate() {
        return scoutRate;
    }

    public void scoutRate(int rate) {
        this.scoutRate = rate;
    }

    public int lastCultureLevel() {
        return lastCultureLevel;
    }

    public void lastCultureLevel(int level) {
        this.lastCultureLevel = level;
        this.lastBorderLevel = Instant.now();
    }

    public Instant lastBorderLevel() {
        return lastBorderLevel;
    }
}
