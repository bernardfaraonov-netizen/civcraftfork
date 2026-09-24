package com.civcraft.model;

import com.civcraft.core.util.ChunkKey;
import com.civcraft.storage.Stored;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A claimed chunk ("plot") belonging to a town. */
public final class Claim implements Stored {

    private static final int DEFAULT_PERMS = defaults();

    private ChunkKey chunk;
    private String townId;
    private UUID owner;
    private Set<String> groups = new HashSet<>();
    /** Bit (subject * 4 + perm) set = allowed. */
    private int perms = DEFAULT_PERMS;
    private long price;
    private boolean mobs;
    private boolean fire;
    /** Claimed automatically by a structure; cannot be unclaimed manually. */
    private boolean locked;

    private Claim() {
    }

    public Claim(ChunkKey chunk, String townId) {
        this.chunk = chunk;
        this.townId = townId;
        groups.add(Town.RESIDENTS);
    }

    private static int defaults() {
        int bits = 0;
        for (PlotPerm p : PlotPerm.values()) {
            bits |= 1 << bit(PlotSubject.OWNER, p);
            bits |= 1 << bit(PlotSubject.GROUP, p);
        }
        return bits;
    }

    private static int bit(PlotSubject subject, PlotPerm perm) {
        return subject.ordinal() * 4 + perm.ordinal();
    }

    @Override
    public String storageId() {
        return chunk.toString();
    }

    public ChunkKey chunk() {
        return chunk;
    }

    public String townId() {
        return townId;
    }

    public void townId(String townId) {
        this.townId = townId;
    }

    public UUID owner() {
        return owner;
    }

    public void owner(UUID owner) {
        this.owner = owner;
    }

    public Set<String> groups() {
        return groups;
    }

    public boolean allowed(PlotSubject subject, PlotPerm perm) {
        return (perms & (1 << bit(subject, perm))) != 0;
    }

    public void set(PlotSubject subject, PlotPerm perm, boolean allowed) {
        if (allowed) perms |= 1 << bit(subject, perm);
        else perms &= ~(1 << bit(subject, perm));
    }

    public void resetPerms() {
        perms = DEFAULT_PERMS;
    }

    public long price() {
        return price;
    }

    public void price(long price) {
        this.price = price;
    }

    public boolean forSale() {
        return price > 0;
    }

    public boolean mobs() {
        return mobs;
    }

    public void mobs(boolean mobs) {
        this.mobs = mobs;
    }

    public boolean fire() {
        return fire;
    }

    public void fire(boolean fire) {
        this.fire = fire;
    }

    public boolean locked() {
        return locked;
    }

    public void locked(boolean locked) {
        this.locked = locked;
    }
}
