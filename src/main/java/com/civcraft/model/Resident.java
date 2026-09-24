package com.civcraft.model;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A player known to CivCraft. Online or offline. */
public final class Resident implements Stored {

    private UUID uuid;
    private String name;
    /** Personal balance in hundredths of a coin. */
    private long balance;
    /** Personal debt (unpaid flat tax etc.), hundredths. */
    private long debt;
    private Instant debtSince;
    private String townId;
    private String campId;
    private Instant firstJoin;
    private Instant lastSeen;
    /** Remaining newbie PvP protection in seconds; counts down only while online. */
    private long pvpProtectionSeconds;
    private String selectedTownId;
    /** civId → when the player last left/was evicted from a town of that civ (12 h rejoin cooldown). */
    private Map<String, Instant> leftCiv = new HashMap<>();
    private Set<UUID> friends = new HashSet<>();
    private Set<String> settings = new HashSet<>();
    private String language;

    private Resident() {
    }

    public Resident(UUID uuid, String name, long startingBalance, long pvpProtectionSeconds) {
        this.uuid = uuid;
        this.name = name;
        this.balance = startingBalance;
        this.firstJoin = Instant.now();
        this.lastSeen = Instant.now();
        this.pvpProtectionSeconds = pvpProtectionSeconds;
    }

    @Override
    public String storageId() {
        return uuid.toString();
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public long balance() {
        return balance;
    }

    /** Adds (or with a negative amount removes) money. Callers must validate sufficiency first. */
    public void addBalance(long cents) {
        this.balance = Math.addExact(this.balance, cents);
    }

    public boolean has(long cents) {
        return balance >= cents;
    }

    public long debt() {
        return debt;
    }

    public void debt(long debt) {
        this.debt = Math.max(0, debt);
        if (this.debt == 0) debtSince = null;
        else if (debtSince == null) debtSince = Instant.now();
    }

    public Instant debtSince() {
        return debtSince;
    }

    public String townId() {
        return townId;
    }

    public void townId(String townId) {
        this.townId = townId;
    }

    public boolean hasTown() {
        return townId != null;
    }

    public String campId() {
        return campId;
    }

    public void campId(String campId) {
        this.campId = campId;
    }

    public Instant firstJoin() {
        return firstJoin;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public void lastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public long pvpProtectionSeconds() {
        return pvpProtectionSeconds;
    }

    public void pvpProtectionSeconds(long seconds) {
        this.pvpProtectionSeconds = Math.max(0, seconds);
    }

    public boolean isPvpProtected() {
        return pvpProtectionSeconds > 0;
    }

    public String selectedTownId() {
        return selectedTownId;
    }

    public void selectedTownId(String id) {
        this.selectedTownId = id;
    }

    public Map<String, Instant> leftCiv() {
        return leftCiv;
    }

    public Set<UUID> friends() {
        return friends;
    }

    public boolean setting(String key) {
        return settings.contains(key);
    }

    public void setting(String key, boolean value) {
        if (value) settings.add(key);
        else settings.remove(key);
    }

    public String language() {
        return language;
    }

    public void language(String language) {
        this.language = language;
    }
}
