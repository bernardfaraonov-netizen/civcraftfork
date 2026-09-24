package com.civcraft.economy;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.core.CivException;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.text.Messages;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;

/**
 * Every money movement between residents, town and civilization treasuries. Amounts are validated
 * (strictly positive), sufficiency is checked before anything changes, and each transfer debits and
 * credits exactly once (the legacy daily tax credited town treasuries twice, audit A-K1). Large
 * treasury operations are announced in town/civ chat unless the civ disabled the money log.
 */
public final class Ledger {

    private Ledger() {
    }

    private static CivCraft civ() {
        return CivCraft.get();
    }

    private static void positive(long cents) throws CivException {
        if (cents <= 0) throw new CivException("error.invalid-amount");
    }

    private static long logThreshold() {
        return civ().balance().coins("economy", "money-log-threshold", 5000);
    }

    // --- residents ------------------------------------------------------------------------------

    public static void pay(Resident from, Resident to, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (from.uuid().equals(to.uuid())) throw new CivException("resident.pay.self");
        if (!from.has(cents)) throw new CivException("error.not-enough-money", Messages.money("amount", cents));
        Math.addExact(to.balance(), cents); // overflow check before anything changes
        from.addBalance(-cents);
        to.addBalance(cents);
        civ().state().save(from);
        civ().state().save(to);
    }

    /** Takes money from a resident (purchases, fees). */
    public static void charge(Resident r, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (!r.has(cents)) throw new CivException("error.not-enough-money", Messages.money("amount", cents));
        r.addBalance(-cents);
        civ().state().save(r);
    }

    /** Gives money to a resident (income, refunds, exchange). */
    public static void credit(Resident r, long cents) {
        Tasks.checkMain();
        if (cents <= 0) return;
        r.addBalance(cents);
        civ().state().save(r);
    }

    // --- towns ----------------------------------------------------------------------------------

    public static void residentToTown(Resident r, Town town, long cents) throws CivException {
        charge(r, cents);
        town.addTreasury(cents);
        civ().state().save(town);
        logTown(town, "economy.log.town-deposit", r.name(), cents);
    }

    public static void townToResident(Town town, Resident r, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (town.treasury() < cents) throw new CivException("economy.town-not-enough", Messages.money("amount", cents));
        town.addTreasury(-cents);
        r.addBalance(cents);
        civ().state().save(town);
        civ().state().save(r);
        logTown(town, "economy.log.town-withdraw", r.name(), cents);
    }

    /** Spends town treasury money (claims, upgrades...). */
    public static void chargeTown(Town town, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (town.treasury() < cents) throw new CivException("economy.town-not-enough", Messages.money("amount", cents));
        town.addTreasury(-cents);
        civ().state().save(town);
    }

    public static void creditTown(Town town, long cents) {
        Tasks.checkMain();
        if (cents <= 0) return;
        town.addTreasury(cents);
        civ().state().save(town);
    }

    /** Takes up to {@code cents} from the treasury; returns what was actually taken. */
    public static long takeUpTo(Town town, long cents) {
        long taken = Math.max(0, Math.min(town.treasury(), cents));
        if (taken > 0) {
            town.addTreasury(-taken);
            civ().state().save(town);
        }
        return taken;
    }

    // --- civilizations --------------------------------------------------------------------------

    public static void residentToCiv(Resident r, Civilization c, long cents) throws CivException {
        charge(r, cents);
        c.addTreasury(cents);
        civ().state().save(c);
        logCiv(c, "economy.log.civ-deposit", r.name(), cents);
    }

    public static void civToResident(Civilization c, Resident r, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (c.treasury() < cents) throw new CivException("economy.civ-not-enough", Messages.money("amount", cents));
        c.addTreasury(-cents);
        r.addBalance(cents);
        civ().state().save(c);
        civ().state().save(r);
        logCiv(c, "economy.log.civ-withdraw", r.name(), cents);
    }

    public static void chargeCiv(Civilization c, long cents) throws CivException {
        Tasks.checkMain();
        positive(cents);
        if (c.treasury() < cents) throw new CivException("economy.civ-not-enough", Messages.money("amount", cents));
        c.addTreasury(-cents);
        civ().state().save(c);
    }

    public static void creditCiv(Civilization c, long cents) {
        Tasks.checkMain();
        if (cents <= 0) return;
        c.addTreasury(cents);
        civ().state().save(c);
    }

    // --- logs -----------------------------------------------------------------------------------

    public static void logTown(Town town, String key, String who, long cents) {
        if (cents < logThreshold()) return;
        Civilization c = civ().state().civOf(town);
        if (c != null && !c.flag("moneylog")) return;
        Channels.town(town, key, Messages.arg("player", who), Messages.money("amount", cents));
    }

    public static void logCiv(Civilization c, String key, String who, long cents) {
        if (cents < logThreshold() || !c.flag("moneylog")) return;
        Channels.civ(c, key, Messages.arg("player", who), Messages.money("amount", cents));
    }
}
