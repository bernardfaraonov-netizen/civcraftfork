package com.civcraft.model;

import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A civilization (or a province, before it builds a Capitol). Research, religion, talents etc. are
 * separate documents owned by their modules.
 */
public final class Civilization implements Stored {

    /** Role levels used by the {@code /civ perm} matrix. */
    public enum Rank {
        OWNER, LEADER, ADVISER, NONE;

        public boolean atLeast(Rank other) {
            return ordinal() <= other.ordinal();
        }
    }

    private String id;
    private String name;
    private String tag;
    /** Fixed tag colour (era index) bought with {@code /civ set ctag}; null = follows the era. */
    private Integer fixedTagColor;
    private String cultureColor;
    private boolean province = true;
    private UUID owner;
    private Set<UUID> leaders = new HashSet<>();
    private Set<UUID> advisers = new HashSet<>();
    private String capitalId;
    private String nation;
    private String government = "anarchy";
    private String targetGovernment;
    private Instant governmentChangeEnds;
    private long treasury;
    private long debt;
    private Instant debtSince;
    /** Share of hourly town income sent to the civ, 0..1. */
    private double taxes = 0.10;
    /** Share of collected taxes converted into beakers, 0..1. */
    private double science = 0.50;
    private Instant founded;
    private int settlersTrained;
    private int warnings;
    /** Permission key → minimum rank. */
    private Map<String, Rank> perms = new HashMap<>();
    /** Permission key → individual players granted regardless of rank. */
    private Map<String, Set<UUID>> personalPerms = new HashMap<>();
    private Set<String> flags = new HashSet<>();
    /** Civ that conquered this one (its capital was captured), null if independent. */
    private String conqueredBy;
    private String motd;

    private Civilization() {
    }

    public Civilization(String id, String name, String tag, UUID owner) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.owner = owner;
        this.founded = Instant.now();
        flags.add("moneylog");
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

    public String tag() {
        return tag;
    }

    public void tag(String tag) {
        this.tag = tag;
    }

    public Integer fixedTagColor() {
        return fixedTagColor;
    }

    public void fixedTagColor(Integer color) {
        this.fixedTagColor = color;
    }

    public String cultureColor() {
        return cultureColor;
    }

    public void cultureColor(String color) {
        this.cultureColor = color;
    }

    public boolean isProvince() {
        return province;
    }

    public void province(boolean province) {
        this.province = province;
    }

    public UUID owner() {
        return owner;
    }

    public void owner(UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> leaders() {
        return leaders;
    }

    public Set<UUID> advisers() {
        return advisers;
    }

    public Rank rank(UUID uuid) {
        if (uuid.equals(owner)) return Rank.OWNER;
        if (leaders.contains(uuid)) return Rank.LEADER;
        if (advisers.contains(uuid)) return Rank.ADVISER;
        return Rank.NONE;
    }

    public boolean isLeader(UUID uuid) {
        return rank(uuid).atLeast(Rank.LEADER);
    }

    public String capitalId() {
        return capitalId;
    }

    public void capitalId(String capitalId) {
        this.capitalId = capitalId;
    }

    public String nation() {
        return nation;
    }

    public void nation(String nation) {
        this.nation = nation;
    }

    public String government() {
        return government;
    }

    public void government(String government) {
        this.government = government;
    }

    public String targetGovernment() {
        return targetGovernment;
    }

    public Instant governmentChangeEnds() {
        return governmentChangeEnds;
    }

    public void startGovernmentChange(String target, Instant ends) {
        this.targetGovernment = target;
        this.governmentChangeEnds = ends;
    }

    public void finishGovernmentChange() {
        if (targetGovernment != null) government = targetGovernment;
        targetGovernment = null;
        governmentChangeEnds = null;
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

    public double taxes() {
        return taxes;
    }

    public void taxes(double taxes) {
        this.taxes = taxes;
    }

    public double science() {
        return science;
    }

    public void science(double science) {
        this.science = science;
    }

    public Instant founded() {
        return founded;
    }

    public int settlersTrained() {
        return settlersTrained;
    }

    public void settlersTrained(int n) {
        this.settlersTrained = n;
    }

    public int warnings() {
        return warnings;
    }

    public void warnings(int warnings) {
        this.warnings = warnings;
    }

    public Map<String, Rank> perms() {
        return perms;
    }

    public Map<String, Set<UUID>> personalPerms() {
        return personalPerms;
    }

    public boolean flag(String key) {
        return flags.contains(key);
    }

    public void flag(String key, boolean value) {
        if (value) flags.add(key);
        else flags.remove(key);
    }

    public String conqueredBy() {
        return conqueredBy;
    }

    public void conqueredBy(String civId) {
        this.conqueredBy = civId;
    }

    public boolean isConquered() {
        return conqueredBy != null;
    }

    public String motd() {
        return motd;
    }

    public void motd(String motd) {
        this.motd = motd;
    }
}
