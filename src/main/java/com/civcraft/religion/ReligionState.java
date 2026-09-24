package com.civcraft.religion;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Religion document of one civilization (collection {@code religion}). */
public final class ReligionState implements Stored {

    /** Values captured when a war starts, compared when it ends (loss conditions). */
    public static final class WarSnapshot {
        double science;
        double culture;
        int towns;
        double capitalUnhappiness;
    }

    private String civId;
    private double points;
    private String religion;
    private Instant adoptedAt;
    private String pendingReligion;
    private Instant changeEnds;
    private Instant noPointsUntil;
    /** Structure counts at adoption (Catholicism / Protestantism loss rule). */
    private Map<String, Integer> adoptionCounts = new HashMap<>();
    private WarSnapshot warSnapshot;
    private Instant prophetCooldownUntil;
    private double hourlyIncome;
    private boolean wasConquered;

    private ReligionState() {
    }

    public ReligionState(String civId) {
        this.civId = civId;
    }

    @Override
    public String storageId() {
        return civId;
    }

    void repair() {
        if (adoptionCounts == null) adoptionCounts = new HashMap<>();
        if (!Double.isFinite(points) || points < 0) points = 0;
    }

    public String civId() {
        return civId;
    }

    public double points() {
        return points;
    }

    public void points(double points) {
        this.points = Math.max(0, Double.isFinite(points) ? points : 0);
    }

    public String religion() {
        return religion;
    }

    public void religion(String religion) {
        this.religion = religion;
        this.adoptedAt = religion == null ? null : Instant.now();
    }

    public Instant adoptedAt() {
        return adoptedAt;
    }

    public String pendingReligion() {
        return pendingReligion;
    }

    public Instant changeEnds() {
        return changeEnds;
    }

    public void pending(String religion, Instant ends) {
        this.pendingReligion = religion;
        this.changeEnds = ends;
    }

    public Instant noPointsUntil() {
        return noPointsUntil;
    }

    public void noPointsUntil(Instant until) {
        this.noPointsUntil = until;
    }

    public boolean pointsBlocked() {
        return noPointsUntil != null && noPointsUntil.isAfter(Instant.now());
    }

    public Map<String, Integer> adoptionCounts() {
        return adoptionCounts;
    }

    public WarSnapshot warSnapshot() {
        return warSnapshot;
    }

    public void warSnapshot(WarSnapshot snapshot) {
        this.warSnapshot = snapshot;
    }

    public Instant prophetCooldownUntil() {
        return prophetCooldownUntil;
    }

    public void prophetCooldownUntil(Instant until) {
        this.prophetCooldownUntil = until;
    }

    public double hourlyIncome() {
        return hourlyIncome;
    }

    public void hourlyIncome(double income) {
        this.hourlyIncome = income;
    }

    public boolean wasConquered() {
        return wasConquered;
    }

    public void wasConquered(boolean v) {
        this.wasConquered = v;
    }
}
