package com.civcraft.model;

import com.civcraft.core.util.BlockPos;
import com.civcraft.storage.Stored;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A camp: the pre-town stage of the game. */
public final class Camp implements Stored {

    private String id;
    private String name;
    private String tag;
    private UUID owner;
    private Set<UUID> members = new HashSet<>();
    /** Minimum corner of the camp template. */
    private BlockPos origin;
    private String rotation;
    private String theme;
    private double hp;
    private Set<String> upgrades = new HashSet<>();
    private int longhouseLevel = 1;
    private int longhouseProgress;
    private long leadershipTokens;
    private Instant founded;
    private String motd;

    private Camp() {
    }

    public Camp(String id, String name, UUID owner, BlockPos origin, String rotation, String theme, double hp) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.origin = origin;
        this.rotation = rotation;
        this.theme = theme;
        this.hp = hp;
        this.founded = Instant.now();
        members.add(owner);
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

    public UUID owner() {
        return owner;
    }

    public void owner(UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> members() {
        return members;
    }

    public BlockPos origin() {
        return origin;
    }

    public String rotation() {
        return rotation;
    }

    public String theme() {
        return theme;
    }

    public double hp() {
        return hp;
    }

    public void hp(double hp) {
        this.hp = hp;
    }

    public Set<String> upgrades() {
        return upgrades;
    }

    public int longhouseLevel() {
        return longhouseLevel;
    }

    public void longhouseLevel(int level) {
        this.longhouseLevel = level;
    }

    public int longhouseProgress() {
        return longhouseProgress;
    }

    public void longhouseProgress(int progress) {
        this.longhouseProgress = progress;
    }

    public long leadershipTokens() {
        return leadershipTokens;
    }

    public void leadershipTokens(long tokens) {
        this.leadershipTokens = tokens;
    }

    public Instant founded() {
        return founded;
    }

    public String motd() {
        return motd;
    }

    public void motd(String motd) {
        this.motd = motd;
    }
}
