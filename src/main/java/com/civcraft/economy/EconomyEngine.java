package com.civcraft.economy;

import com.civcraft.CivCraft;
import com.civcraft.chat.Channels;
import com.civcraft.clock.GameClock;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Money;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.effect.EffectSink;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Op;
import com.civcraft.effect.Scope;
import com.civcraft.effect.StatSheet;
import com.civcraft.effect.Stats;
import com.civcraft.event.BeakersProducedEvent;
import com.civcraft.event.TaxesConvertedEvent;
import com.civcraft.government.GovernmentModule;
import com.civcraft.model.Claim;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.plot.PlotModule;
import com.civcraft.science.ResearchApi;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownModule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The economy clock (spec §8–§12): hourly culture, income, civ taxes and beakers; daily bank
 * interest, resident taxes, upkeep and debt consequences; the biome effect provider. Every money
 * movement happens exactly once per tick and on the main thread.
 */
public final class EconomyEngine {

    public static final String BANK_INTEREST = "bank_interest";
    public static final String ENEMY_WAR_UPKEEP = "enemy_war_upkeep_multiplier";

    private final CivCraft civ;
    private final TownModule towns;
    private final BiomeTable biomes;
    private final BiomeCache biomeCache;
    private boolean warnedNoUpkeepSource;

    public EconomyEngine(CivCraft civ, TownModule towns) {
        this.civ = civ;
        this.towns = towns;
        this.biomes = new BiomeTable(civ.balance().file("biomes"));
        this.biomeCache = new BiomeCache(civ);
    }

    public void load() {
        biomeCache.load();
    }

    public void enable() {
        civ.listen(biomeCache);
        biomeCache.onChange(() -> {
            civ.stats().invalidate();
            towns.production().invalidate();
        });
        civ.stats().register(this::contributeBiomes);
        civ.clock().everySecond("economy-cache", () -> {
            biomeCache.tick();
            towns.production().invalidate();
        });
        civ.clock().hourly(GameClock.PRODUCTION, "town-culture", this::hourlyCulture);
        civ.clock().hourly(GameClock.INCOME, "town-income", this::hourlyIncome);
        civ.clock().hourly(GameClock.CONSEQUENCES, "auto-capitulation", this::autoCapitulate);
        civ.clock().daily(GameClock.INCOME, "bank-interest", this::dailyInterest);
        civ.clock().daily(GameClock.TAXES, "resident-taxes", this::dailyResidentTaxes);
        civ.clock().daily(GameClock.UPKEEP, "town-upkeep", this::dailyUpkeep);
        civ.clock().daily(GameClock.CONSEQUENCES, "debts-and-burning", this::dailyConsequences);
    }

    public BiomeTable biomes() {
        return biomes;
    }

    public BiomeCache biomeCache() {
        return biomeCache;
    }

    // --- effects --------------------------------------------------------------------------------

    /** Biome values of culture chunks and main-building culture as modifiers (source "biome"/"building"). */
    private void contributeBiomes(EffectSink sink) {
        boolean mainCulture = civ.balance().file("economy").getBoolean("main-building-culture", false);
        for (Town town : civ.state().towns()) {
            BiomeTable.Values sum = BiomeTable.Values.ZERO;
            for (ChunkKey chunk : civ.culture().chunks(town)) sum = sum.plus(biomes.values(biomeCache.biome(chunk)));
            if (sum.hammers() != 0) sink.town(town, new Modifier(Stats.HAMMERS, Op.ADD, sum.hammers(), Scope.TOWN, "biome"));
            if (sum.growth() != 0) sink.town(town, new Modifier(Stats.GROWTH, Op.ADD, sum.growth(), Scope.TOWN, "biome"));
            if (sum.happiness() != 0) sink.town(town, new Modifier(Stats.HAPPINESS, Op.ADD, sum.happiness(), Scope.TOWN, "biome"));
            if (sum.beakers() != 0) sink.town(town, new Modifier(Stats.BEAKERS, Op.ADD, sum.beakers(), Scope.TOWN, "biome"));
            if (mainCulture) {
                if (towns.service().hasCompleteCapitol(town)) {
                    sink.town(town, new Modifier(Stats.CULTURE, Op.ADD, civ.balance().getDouble("core", "culture.capitol", 50),
                            Scope.TOWN, "structure:capitol"));
                } else if (towns.service().mainBuildingComplete(town) && civ.apiOrNull(StructureApi.class) != null) {
                    sink.town(town, new Modifier(Stats.CULTURE, Op.ADD, civ.balance().getDouble("core", "culture.town-hall", 25),
                            Scope.TOWN, "structure:town_hall"));
                }
            }
        }
    }

    /** Number of culture chunks of a town whose biome belongs to a class (desert, jungle, ocean, river). */
    public int chunksOfClass(Town town, String cls) {
        int n = 0;
        for (ChunkKey chunk : civ.culture().chunks(town)) if (biomes.inClass(biomeCache.biome(chunk), cls)) n++;
        return n;
    }

    // --- hourly ---------------------------------------------------------------------------------

    private void hourlyCulture() {
        boolean levelChanged = false;
        for (Town town : List.copyOf(civ.state().towns())) {
            double gained = towns.production().figures(town).culture();
            if (gained <= 0) continue;
            int before = civ.culture().level(town);
            town.culture(town.culture() + gained);
            civ.state().save(town);
            int after = civ.culture().level(town);
            if (after != before) {
                levelChanged = true;
                Channels.town(town, "economy.culture.level-up", Messages.arg("level", after));
            }
        }
        if (levelChanged) civ.culture().recompute(true);
        else civ.culture().invalidate();
        civ.stats().invalidate();
        towns.production().invalidate();
    }

    /** Hourly income of every town, split into town share, civ treasury and beakers (spec §8.2). */
    private void hourlyIncome() {
        GovernmentModule gov = civ.module(GovernmentModule.class);
        for (Town town : List.copyOf(civ.state().towns())) {
            Production.Figures f = towns.production().figures(town);
            long income = town.disbanding() ? 0 : Money.ofCoins(f.income());
            double converted = split(town, income, gov);
            Civilization c = civ.state().civOf(town);
            // Fired every hour even with 0: research keeps the latest hourly rate per town.
            if (c != null) new BeakersProducedEvent(c.id(), town.id(), Math.max(0, f.beakers() + converted)).call();
        }
    }

    /**
     * Deposits income produced outside of the {@code income} stat (cottages, trade ships...) applying the
     * civilization's income tax and science conversion exactly like the hourly tick. Converted beakers
     * go straight to the current research.
     */
    public void depositIncome(Town town, long cents) {
        if (cents <= 0) return;
        double converted = split(town, cents, civ.module(GovernmentModule.class));
        Civilization c = civ.state().civOf(town);
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        if (c != null && research != null && converted > 0) research.addBeakers(c, converted);
    }

    /** Credits town and civ shares exactly once; returns the beakers bought with the science share. */
    private double split(Town town, long income, GovernmentModule gov) {
        Civilization c = civ.state().civOf(town);
        if (town.disbanding() || income <= 0) return 0;
        if (c == null) {
            Ledger.creditTown(town, income);
            return 0;
        }
        EconomyMath.TaxSplit split = EconomyMath.splitIncome(income, gov.effectiveTaxes(c), c.science());
        Ledger.creditTown(town, split.townShare());
        Ledger.creditCiv(c, split.civTreasury());
        double converted = EconomyMath.beakers(split.scienceCoins(), gov.beakerPrice(c));
        towns.recordTaxes(c, split.civTreasury() + split.scienceCoins(), split.scienceCoins());
        if (split.scienceCoins() > 0) new TaxesConvertedEvent(c.id(), town.id(), split.scienceCoins(), converted).call();
        return converted;
    }

    /** Captured towns capitulate automatically 7 days after capture (spec §17). */
    private void autoCapitulate() {
        Duration after = Duration.ofDays(civ.balance().getInt("diplomacy", "auto-capitulate-days", 7));
        for (Town town : List.copyOf(civ.state().towns())) {
            if (town.status() != TownStatus.CAPTURED || town.capturedAt() == null) continue;
            if (town.capturedAt().plus(after).isAfter(Instant.now())) continue;
            towns.capitulate(town);
        }
    }

    // --- daily ----------------------------------------------------------------------------------

    private void dailyInterest() {
        for (Town town : List.copyOf(civ.state().towns())) {
            double rate = civ.stats().town(town).get(BANK_INTEREST);
            if (rate <= 0 || town.treasury() <= 0) continue;
            long interest = Money.multiply(town.treasury(), Math.min(rate, 1));
            if (interest <= 0) continue;
            Ledger.creditTown(town, interest);
            Channels.town(town, "economy.interest", Messages.money("amount", interest));
        }
    }

    /** Flat tax and property tax from residents into their town treasury (spec §8.4), once per day. */
    private void dailyResidentTaxes() {
        boolean exemptOfficials = civ.balance().file("economy").getBoolean("officials-exempt-from-taxes", true);
        PlotModule plots = civ.apiOrNull(PlotModule.class);
        for (Town town : List.copyOf(civ.state().towns())) {
            if (town.flatTax() <= 0 && town.taxRate() <= 0) continue;
            long collected = 0;
            for (UUID id : List.copyOf(town.residents())) {
                Resident r = civ.state().resident(id);
                if (r == null || (exemptOfficials && town.isOfficial(id))) continue;
                long due = Math.max(0, town.flatTax());
                if (town.taxRate() > 0 && plots != null) {
                    for (Claim claim : civ.state().claims(town)) {
                        if (id.equals(claim.owner())) due += Money.multiply(plots.value(claim), Math.min(1, town.taxRate()));
                    }
                }
                if (due <= 0) continue;
                long paid = Math.min(Math.max(0, r.balance()), due);
                if (paid > 0) {
                    r.addBalance(-paid);
                    town.addTreasury(paid);
                    collected += paid;
                }
                if (paid < due) r.debt(r.debt() + (due - paid));
                civ.state().save(r);
                Channels.resident(r, paid < due ? "economy.tax.partial" : "economy.tax.paid",
                        Messages.money("amount", paid), Messages.money("debt", r.debt()), Messages.arg("town", town.name()));
            }
            civ.state().save(town);
            if (collected > 0) Channels.town(town, "economy.tax.collected", Messages.money("amount", collected));
        }
    }

    /** Detailed upkeep of a town (spec §8.3), in hundredths, for /t info upkeep and the daily tick. */
    public record UpkeepBreakdown(long level, Map<String, Long> structures, double government, double war,
                                  double warReduction, int towns, long total) {
    }

    public UpkeepBreakdown upkeep(Town town) {
        long level = civ.balance().coins("core", "town.levels." + town.level() + ".upkeep", 0);
        Map<String, Long> structures = new LinkedHashMap<>();
        com.civcraft.structure.StructureModule sm = civ.apiOrNull(com.civcraft.structure.StructureModule.class);
        boolean any = sm != null;
        if (sm != null) {
            long s = sm.structuresUpkeep(town);
            if (s > 0) structures.put(civ.messages().plain("economy.upkeep.structures"), s);
        }
        for (com.civcraft.Module m : civ.modules()) {
            if (m instanceof StructureUpkeepSource source) {
                any = true;
                try {
                    source.structureUpkeep(town).forEach((k, v) -> structures.merge(k, Math.max(0, v), Long::sum));
                } catch (RuntimeException e) {
                    civ.logger().log(Level.SEVERE, "Structure upkeep source failed", e);
                }
            }
        }
        if (!any && !warnedNoUpkeepSource) {
            warnedNoUpkeepSource = true;
            civ.logger().warning("No StructureUpkeepSource module installed: structure upkeep is not charged");
        }
        long structureSum = structures.values().stream().mapToLong(Long::longValue).sum();
        StatSheet sheet = civ.stats().town(town);
        double gov = sheet.apply(Stats.UPKEEP, 1.0);
        Civilization c = civ.state().civOf(town);
        double war = 1;
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        if (c != null && dip != null && dip.isAggressor(c.id())) {
            war = civ.balance().getDouble("economy", "war-upkeep.multiplier", 2.5);
            for (String enemy : dip.enemies(c.id())) {
                Civilization e = civ.state().civ(enemy);
                if (e != null) war = Math.max(war, civ.stats().civ(e).get(ENEMY_WAR_UPKEEP));
            }
        }
        double reduction = sheet.get(Stats.WAR_UPKEEP_REDUCTION);
        int count = c == null ? 1 : civ.state().towns(c).size();
        long total = EconomyMath.upkeep(level, structureSum, gov, war, reduction,
                civ.balance().getDouble("core", "town.town-count-upkeep-percent", 0.03), count);
        return new UpkeepBreakdown(level, structures, gov, war, reduction, count, total);
    }

    private void dailyUpkeep() {
        for (Town town : List.copyOf(civ.state().towns())) {
            long due = upkeep(town).total();
            long owed = Math.addExact(due, town.debt());
            long paid = Ledger.takeUpTo(town, owed);
            town.debt(owed - paid);
            civ.state().save(town);
            if (town.debt() > 0) {
                Channels.town(town, "economy.upkeep.debt", Messages.money("amount", paid), Messages.money("debt", town.debt()));
            } else {
                Channels.town(town, "economy.upkeep.paid", Messages.money("amount", paid));
            }
        }
        towns.production().invalidate();
    }

    private void dailyConsequences() {
        // Residents in tax debt for too long are evicted (spec §8.4 assumption).
        Duration residentGrace = Duration.ofDays(civ.balance().getInt("economy", "resident-debt-evict-days", 7));
        for (Resident r : List.copyOf(civ.state().residents())) {
            if (r.debt() <= 0 || r.debtSince() == null || !r.hasTown()) continue;
            if (r.debtSince().plus(residentGrace).isAfter(Instant.now())) continue;
            Town town = civ.state().townOf(r);
            Channels.resident(r, "economy.tax.evicted", Messages.arg("town", town.name()));
            towns.service().removeResident(town, r, true, civ.messages().plain("economy.tax.evictor"), true);
        }
        // Burning towns lose one culture level per daily tick, and disappear at level 1 (spec §6.9).
        boolean cultureChanged = false;
        for (Town town : new ArrayList<>(civ.state().towns())) {
            if (!town.disbanding()) continue;
            int level = civ.culture().level(town);
            if (level <= 1) {
                towns.service().delete(town, "town.burned-down");
                continue;
            }
            town.culture(civ.culture().required(town, level - 1));
            civ.state().save(town);
            cultureChanged = true;
            Channels.town(town, "town.disband.burning", Messages.arg("level", level - 1));
        }
        if (cultureChanged) civ.culture().recompute(true);
    }
}
