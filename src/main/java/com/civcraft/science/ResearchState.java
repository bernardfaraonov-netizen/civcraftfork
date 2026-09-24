package com.civcraft.science;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Research document of one civilization (collection {@code research}). */
public final class ResearchState implements Stored {

    private String civId;
    /** Tech being researched, or null. */
    private String current;
    /** Beakers accumulated per tech (current and interrupted researches). */
    private Map<String, Double> progress = new HashMap<>();
    private List<String> queue = new ArrayList<>();
    /** Researched techs in completion order (the last entries are lost on revolution). */
    private List<String> completed = new ArrayList<>();
    private Map<String, Instant> researchedAt = new HashMap<>();
    /** Beakers produced while nothing was being researched, and overflow of finished techs. */
    private double storedBeakers;
    private int era;
    /** Last announced progress step of the current research (in {@code progress-step-percent} units). */
    private int announcedStep;
    /** Latest hourly beakers reported per town by {@code BeakersProducedEvent}. */
    private Map<String, Double> townRates = new HashMap<>();
    /** Civ science per hour after civ-wide multipliers, refreshed every minute. */
    private double civRate;
    /** Coins (hundredths) converted into beakers from taxes: total and per last hour. */
    private long taxCoinsTotal;
    private double taxBeakersTotal;
    private Map<String, Long> taxCoinsLastHour = new HashMap<>();
    private Map<String, Double> taxBeakersLastHour = new HashMap<>();
    private Instant queueWarnedAt;
    private Instant startedAt;

    private ResearchState() {
    }

    public ResearchState(String civId) {
        this.civId = civId;
    }

    @Override
    public String storageId() {
        return civId;
    }

    /** Gson leaves collections null for fields missing in old documents. */
    void repair() {
        if (progress == null) progress = new HashMap<>();
        if (queue == null) queue = new ArrayList<>();
        if (completed == null) completed = new ArrayList<>();
        if (researchedAt == null) researchedAt = new HashMap<>();
        if (townRates == null) townRates = new HashMap<>();
        if (taxCoinsLastHour == null) taxCoinsLastHour = new HashMap<>();
        if (taxBeakersLastHour == null) taxBeakersLastHour = new HashMap<>();
        if (!Double.isFinite(storedBeakers) || storedBeakers < 0) storedBeakers = 0;
        progress.values().removeIf(v -> v == null || !Double.isFinite(v) || v < 0);
    }

    public String civId() {
        return civId;
    }

    public String current() {
        return current;
    }

    public void current(String tech) {
        this.current = tech;
        this.announcedStep = 0;
        this.startedAt = tech == null ? null : Instant.now();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public double progress(String tech) {
        return progress.getOrDefault(tech, 0.0);
    }

    public void progress(String tech, double beakers) {
        if (beakers <= 0) progress.remove(tech);
        else progress.put(tech, beakers);
    }

    public Map<String, Double> progressMap() {
        return progress;
    }

    public List<String> queue() {
        return queue;
    }

    public List<String> completed() {
        return completed;
    }

    public Map<String, Instant> researchedAt() {
        return researchedAt;
    }

    public double storedBeakers() {
        return storedBeakers;
    }

    public void storedBeakers(double v) {
        this.storedBeakers = Math.max(0, v);
    }

    public int era() {
        return era;
    }

    public void era(int era) {
        this.era = era;
    }

    public int announcedStep() {
        return announcedStep;
    }

    public void announcedStep(int step) {
        this.announcedStep = step;
    }

    public Map<String, Double> townRates() {
        return townRates;
    }

    public double civRate() {
        return civRate;
    }

    public void civRate(double rate) {
        this.civRate = rate;
    }

    public long taxCoinsTotal() {
        return taxCoinsTotal;
    }

    public double taxBeakersTotal() {
        return taxBeakersTotal;
    }

    public Map<String, Long> taxCoinsLastHour() {
        return taxCoinsLastHour;
    }

    public Map<String, Double> taxBeakersLastHour() {
        return taxBeakersLastHour;
    }

    public void recordTaxes(String townId, long coins, double beakers) {
        taxCoinsTotal = Math.addExact(taxCoinsTotal, coins);
        taxBeakersTotal += beakers;
        taxCoinsLastHour.put(townId, coins);
        taxBeakersLastHour.put(townId, beakers);
    }

    public Instant queueWarnedAt() {
        return queueWarnedAt;
    }

    public void queueWarnedAt(Instant at) {
        this.queueWarnedAt = at;
    }
}
