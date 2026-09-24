package com.civcraft.model;

import com.civcraft.core.util.BlockPos;
import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A town. Only core, cross-cutting state lives here; subsystems (structures, research, religion...)
 * keep their own documents keyed by town id so they can evolve independently.
 */
public final class Town implements Stored {

    public static final String MAYORS = "mayors";
    public static final String ASSISTANTS = "assistants";
    public static final String RESIDENTS = "residents";
    public static final Set<String> PROTECTED_GROUPS = Set.of(MAYORS, ASSISTANTS, RESIDENTS);

    private String id;
    private String name;
    private String civId;
    /** Civilization that founded the town (restored on liberation / revolution). */
    private String nativeCivId;
    private TownStatus status = TownStatus.NATIVE;
    /** Status before capture, restored when the town is liberated. */
    private TownStatus statusBeforeCapture;
    private int level = 1;
    private long treasury;
    private long debt;
    private Instant debtSince;
    /** Group name → members. Includes the three built-in groups. */
    private Map<String, Set<UUID>> groups = new LinkedHashMap<>();
    /** Groups that only the civ owner may manage. */
    private Set<String> ownerGroups = new HashSet<>();
    private Instant founded;
    /** Accumulated culture points. */
    private double culture;
    private Set<UUID> outlaws = new HashSet<>();
    private Map<String, Double> fees = new HashMap<>();
    private long flatTax;
    private double taxRate;
    /** Center of the town hall / capitol; the anchor for culture and distances. */
    private BlockPos center;
    private boolean disbanding;
    private boolean mayorConfirmedDisband;
    private boolean civConfirmedDisband;
    private Instant chammersUntil;
    private int warnings;
    private String theme;
    private Instant capturedAt;

    private Town() {
    }

    public Town(String id, String name, String civId, BlockPos center) {
        this.id = id;
        this.name = name;
        this.civId = civId;
        this.nativeCivId = civId;
        this.center = center;
        this.founded = Instant.now();
        groups.put(MAYORS, new HashSet<>());
        groups.put(ASSISTANTS, new HashSet<>());
        groups.put(RESIDENTS, new HashSet<>());
    }

    @Override
    public String storageId() {
        return id;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public String civId() {
        return civId;
    }

    public void civId(String civId) {
        this.civId = civId;
    }

    public String nativeCivId() {
        return nativeCivId;
    }

    public void nativeCivId(String id) {
        this.nativeCivId = id;
    }

    public TownStatus status() {
        return status;
    }

    public void status(TownStatus status) {
        this.status = status;
    }

    public TownStatus statusBeforeCapture() {
        return statusBeforeCapture;
    }

    public void statusBeforeCapture(TownStatus s) {
        this.statusBeforeCapture = s;
    }

    public boolean isCaptured() {
        return status == TownStatus.CAPTURED;
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = level;
    }

    public long treasury() {
        return treasury;
    }

    public void addTreasury(long cents) {
        this.treasury = Math.addExact(this.treasury, cents);
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

    public boolean inDebt() {
        return debt > 0;
    }

    public Map<String, Set<UUID>> groups() {
        return groups;
    }

    public Set<UUID> group(String name) {
        return groups.getOrDefault(name, Set.of());
    }

    public Set<UUID> residents() {
        return groups.get(RESIDENTS);
    }

    public Set<UUID> mayors() {
        return groups.get(MAYORS);
    }

    public Set<UUID> assistants() {
        return groups.get(ASSISTANTS);
    }

    public boolean isMayor(UUID uuid) {
        return mayors().contains(uuid);
    }

    public boolean isAssistant(UUID uuid) {
        return assistants().contains(uuid);
    }

    public boolean isOfficial(UUID uuid) {
        return isMayor(uuid) || isAssistant(uuid);
    }

    public boolean isResident(UUID uuid) {
        return residents().contains(uuid);
    }

    public Set<String> ownerGroups() {
        return ownerGroups;
    }

    public Instant founded() {
        return founded;
    }

    public double culture() {
        return culture;
    }

    public void culture(double culture) {
        this.culture = Math.max(0, culture);
    }

    public Set<UUID> outlaws() {
        return outlaws;
    }

    public double fee(String key, double def) {
        return fees.getOrDefault(key, def);
    }

    public void setFee(String key, double value) {
        fees.put(key, value);
    }

    public long flatTax() {
        return flatTax;
    }

    public void flatTax(long flatTax) {
        this.flatTax = flatTax;
    }

    public double taxRate() {
        return taxRate;
    }

    public void taxRate(double taxRate) {
        this.taxRate = taxRate;
    }

    public BlockPos center() {
        return center;
    }

    public void center(BlockPos center) {
        this.center = center;
    }

    public boolean disbanding() {
        return disbanding;
    }

    public void disbanding(boolean disbanding) {
        this.disbanding = disbanding;
    }

    public boolean mayorConfirmedDisband() {
        return mayorConfirmedDisband;
    }

    public void mayorConfirmedDisband(boolean v) {
        this.mayorConfirmedDisband = v;
    }

    public boolean civConfirmedDisband() {
        return civConfirmedDisband;
    }

    public void civConfirmedDisband(boolean v) {
        this.civConfirmedDisband = v;
    }

    public Instant chammersUntil() {
        return chammersUntil;
    }

    public void chammersUntil(Instant until) {
        this.chammersUntil = until;
    }

    public boolean convertingHammers() {
        return chammersUntil != null && chammersUntil.isAfter(Instant.now());
    }

    public int warnings() {
        return warnings;
    }

    public void warnings(int warnings) {
        this.warnings = warnings;
    }

    public String theme() {
        return theme;
    }

    public void theme(String theme) {
        this.theme = theme;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public void capturedAt(Instant at) {
        this.capturedAt = at;
    }
}
