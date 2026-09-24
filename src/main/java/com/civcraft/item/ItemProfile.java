package com.civcraft.item;

import com.civcraft.storage.Stored;
import java.util.UUID;

/**
 * Per-player item state: whether the starting kit was given and the hidden personal sharpening bonus
 * (spec 04 §6.3: grows after each failure, resets on success, never shown to the player).
 */
public final class ItemProfile implements Stored {

    public static final String COLLECTION = "item_profiles";

    private UUID uuid;
    private boolean kitGiven;
    private double personalChance;

    private ItemProfile() {
    }

    public ItemProfile(UUID uuid) {
        this.uuid = uuid;
    }

    @Override
    public String storageId() {
        return uuid.toString();
    }

    public UUID uuid() {
        return uuid;
    }

    public boolean kitGiven() {
        return kitGiven;
    }

    public void kitGiven(boolean value) {
        this.kitGiven = value;
    }

    public double personalChance() {
        return Double.isFinite(personalChance) ? personalChance : 0;
    }

    public void personalChance(double value) {
        this.personalChance = Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
