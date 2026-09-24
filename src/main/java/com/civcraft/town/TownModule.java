package com.civcraft.town;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.chat.Channels;
import com.civcraft.civ.CivModule;
import com.civcraft.civ.CivPerms;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Messages;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.diplomacy.DiplomacyApi;
import com.civcraft.economy.EconomyEngine;
import com.civcraft.economy.EconomyMath;
import com.civcraft.economy.Production;
import com.civcraft.effect.Stats;
import com.civcraft.event.CultureChangedEvent;
import com.civcraft.event.StructureCompletedEvent;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.model.Civilization;
import com.civcraft.model.Resident;
import com.civcraft.model.RelationType;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.protection.Action;
import com.civcraft.protection.Guard;
import com.civcraft.protection.Verdict;
import com.civcraft.science.ResearchApi;
import com.civcraft.structure.StructureApi;
import com.civcraft.town.TownData.Job;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Towns (spec §6–§12): the {@link TownApi}, membership, claims, groups, upgrades, teleports,
 * disbanding, outlaws, fees and taxes, info pages, and the economy engine.
 */
public final class TownModule implements Module, TownApi, Listener {

    private CivCraft civ;
    private TownService service;
    private Production production;
    private EconomyEngine economy;
    private final Upgrades upgrades = new Upgrades();
    private final Map<String, TownData> data = new HashMap<>();
    private final TreeMap<Integer, Long> claimPrices = new TreeMap<>();
    private final Map<String, long[]> taxRecords = new HashMap<>();
    private final Map<UUID, String> lastCultureTown = new HashMap<>();
    private final Map<String, Instant> borderNotified = new HashMap<>();
    private boolean warnedNoResearch;
    private TownCommands commands;

    @Override
    public String id() {
        return "town";
    }

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("town");
        civ.messages().include("economy");
        civ.store().createCollection(TownData.COLLECTION);
        for (TownData d : civ.store().loadAll(TownData.COLLECTION, TownData.class)) {
            if (civ.state().town(d.townId()) != null) data.put(d.townId(), d);
            else civ.saves().delete(TownData.COLLECTION, d.storageId());
        }
        service = new TownService(civ, this);
        production = new Production(civ);
        economy = new EconomyEngine(civ, this);
        economy.load();
        var prices = civ.balance().section("core", "town.claim-prices");
        for (String key : prices.getKeys(false)) {
            claimPrices.put(Integer.parseInt(key), com.civcraft.core.util.Money.ofCoins(prices.getDouble(key)));
        }
        if (claimPrices.isEmpty()) claimPrices.put(Integer.MAX_VALUE, 50000L);
        upgrades.loadBuiltIns(civ, this::completeTownLevel);
    }

    @Override
    public void enable(CivCraft civ) {
        economy.enable();
        civ.listen(this);
        civ.protection().register(new CultureGuard());
        civ.clock().everyMinute("town-upgrades", this::tickUpgrades);
        commands = new TownCommands(civ, this);
        commands.register();
    }

    // --- accessors ------------------------------------------------------------------------------

    public TownService service() {
        return service;
    }

    public Production production() {
        return production;
    }

    public EconomyEngine economy() {
        return economy;
    }

    public Upgrades upgrades() {
        return upgrades;
    }

    /** Shared /town handlers reused by /civ (teleport, disband). */
    public TownCommands commands() {
        return commands;
    }

    public TownData data(Town town) {
        return data.computeIfAbsent(town.id(), TownData::new);
    }

    public void saveData(Town town) {
        civ.saves().save(TownData.COLLECTION, data(town));
    }

    void deleteData(Town town) {
        data.remove(town.id());
        civ.saves().delete(TownData.COLLECTION, town.id());
    }

    void onCivDeleted(Civilization c) {
        taxRecords.remove(c.id());
        CivModule civModule = civ.apiOrNull(CivModule.class);
        if (civModule != null) civModule.deleteData(c);
    }

    /** Whether the town completed the upgrade (for other modules' requirements). */
    public boolean hasUpgrade(Town town, String upgradeId) {
        return data(town).upgrades().contains(upgradeId);
    }

    // --- TownApi --------------------------------------------------------------------------------

    @Override
    public double hammersPerHour(Town town) {
        return production.figures(town).hammers();
    }

    @Override
    public double beakersPerHour(Town town) {
        return production.figures(town).beakers();
    }

    @Override
    public double culturePerHour(Town town) {
        return production.figures(town).culture();
    }

    @Override
    public double faithPerHour(Town town) {
        return production.figures(town).faith();
    }

    @Override
    public double incomePerHour(Town town) {
        return production.figures(town).income();
    }

    @Override
    public double growth(Town town) {
        return production.figures(town).growth();
    }

    @Override
    public double happinessPercent(Town town) {
        return production.figures(town).happyPercent();
    }

    @Override
    public double happinessMultiplier(Town town) {
        return production.figures(town).state().multiplier();
    }

    @Override
    public int slots(Town town) {
        int base = civ.balance().getInt("core", "town.levels." + town.level() + ".slots", 4);
        return Math.max(0, (int) Math.floor(base + civ.stats().town(town).get(Stats.SLOTS)));
    }

    @Override
    public long dailyUpkeep(Town town) {
        return economy.upkeep(town).total();
    }

    @Override
    public int cultureLevel(Town town) {
        return civ.culture().level(town);
    }

    @Override
    public boolean canManage(Player player, Town town) {
        if (town.isOfficial(player.getUniqueId())) return true;
        Civilization c = civ.state().civOf(town);
        return c != null && c.isLeader(player.getUniqueId());
    }

    @Override
    public void checkCivPerm(Player player, Civilization c, String perm) throws CivException {
        CivPerms.check(player, c, perm);
    }

    @Override
    public Town selectedTown(Player player) throws CivException {
        Resident r = civ.state().resident(player);
        if (r == null) throw new CivException("error.not-in-town");
        Town selected = civ.state().town(r.selectedTownId());
        if (selected != null && canSelect(player, selected)) return selected;
        if (selected != null) {
            r.selectedTownId(null);
            civ.state().save(r);
        }
        Town own = civ.state().townOf(r);
        if (own == null) throw new CivException("error.not-in-town");
        return own;
    }

    /** /t select rules (spec §6.5): officials and residents of the town, leaders/advisers of its civ. */
    public boolean canSelect(Player p, Town town) {
        UUID id = p.getUniqueId();
        if (town.isResident(id) || town.isOfficial(id)) return true;
        Civilization c = civ.state().civOf(town);
        return c != null && c.rank(id).atLeast(Civilization.Rank.ADVISER);
    }

    /** Throws unless the player is a mayor/assistant of the town or a leader of its civilization. */
    public void requireManage(Player p, Town town) throws CivException {
        if (!canManage(p, town)) throw new CivException("town.no-rights", Messages.arg("town", town.name()));
    }

    // --- claims ---------------------------------------------------------------------------------

    public int claimLimit(Town town) {
        return civ.balance().getInt("core", "town.levels." + town.level() + ".claims", 32);
    }

    /** Price of the next claim of the town (spec §6.4). */
    public long nextClaimPrice(Town town) {
        return EconomyMath.claimPrice(claimPrices, civ.state().claimCount(town) + 1);
    }

    /** Claims and unclaims are locked from N hours before the war window until its end (spec §6.4). */
    public boolean claimsLocked() {
        DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
        return dip != null && dip.isWarWithin(Duration.ofHours(civ.balance().getInt("core", "town.claim-lock-hours-before-war", 3)));
    }

    // --- research helper ------------------------------------------------------------------------

    /** Whether a civilization has a technology; false (logged once) without a research module. */
    public boolean hasTech(Civilization c, String tech) {
        if (tech == null || tech.isBlank()) return true;
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        if (research == null) {
            if (!warnedNoResearch) {
                warnedNoResearch = true;
                civ.logger().warning("No research module: requirements that need a technology cannot be met");
            }
            return false;
        }
        return c != null && research.hasTech(c, tech);
    }

    public String techName(String tech) {
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        return research == null || !research.techExists(tech) ? tech : research.techName(tech);
    }

    // --- upgrades -------------------------------------------------------------------------------

    private void completeTownLevel(Town town, Upgrades.Def def) {
        int level = Upgrades.townLevel(def.id());
        if (level > town.level()) {
            town.level(level);
            civ.state().save(town);
            civ.stats().invalidate();
            production.invalidate();
        }
    }

    /** Upgrade jobs collect hammers every minute: hammers/h / 60 / parallel jobs (spec §12). */
    private void tickUpgrades() {
        com.civcraft.government.GovernmentModule gov = civ.apiOrNull(com.civcraft.government.GovernmentModule.class);
        for (Town town : List.copyOf(civ.state().towns())) {
            TownData d = data.get(town.id());
            if (d == null || d.jobs().isEmpty()) continue;
            Civilization c = civ.state().civOf(town);
            if (town.convertingHammers() || (c != null && gov != null && gov.isAnarchy(c))) continue;
            double perJob = hammersPerHour(town) / 60.0 / d.jobs().size();
            if (perJob <= 0) continue;
            boolean changed = false;
            for (Iterator<Job> it = d.jobs().iterator(); it.hasNext(); ) {
                Job job = it.next();
                job.progress(Math.min(job.required(), job.progress() + perJob));
                changed = true;
                if (job.progress() + 1e-9 < job.required()) continue;
                it.remove();
                Upgrades.Def def = upgrades.get(job.upgrade());
                d.upgrades().add(job.upgrade());
                if (def != null) {
                    upgrades.complete(town, def);
                    Channels.town(town, "town.upgrade.complete", Messages.arg("upgrade", def.name()));
                }
            }
            if (changed) saveData(town);
        }
    }

    // --- taxes record (for /civ info taxes) -----------------------------------------------------

    public void recordTaxes(Civilization c, long total, long science) {
        long hour = Instant.now().getEpochSecond() / 3600;
        long[] r = taxRecords.computeIfAbsent(c.id(), k -> new long[3]);
        if (r[0] != hour) {
            r[0] = hour;
            r[1] = 0;
            r[2] = 0;
        }
        r[1] += total;
        r[2] += science;
    }

    /** Taxes collected in the last hourly tick: {total, converted to science}, hundredths. */
    public long[] lastTaxes(Civilization c) {
        long[] r = taxRecords.get(c.id());
        return r == null ? new long[]{0, 0} : new long[]{r[1], r[2]};
    }

    // --- capitulation, wonders, logs ------------------------------------------------------------

    /**
     * A captured town capitulates: it becomes Affiliated and forgets its homeland (spec §17). If it was
     * the capital of its native civilization, every town captured from that civ capitulates too and the
     * conquered civilization disappears.
     */
    public void capitulate(Town town) {
        if (town.status() != TownStatus.CAPTURED) return;
        Civilization nativeCiv = civ.state().civ(town.nativeCivId());
        boolean capital = nativeCiv != null && town.id().equals(nativeCiv.capitalId());
        affiliate(town);
        if (capital) {
            for (Town t : List.copyOf(civ.state().towns())) {
                if (t.status() == TownStatus.CAPTURED && Objects.equals(t.nativeCivId(), nativeCiv.id())) affiliate(t);
            }
            if (civ.state().towns(nativeCiv).isEmpty()) service.deleteCiv(nativeCiv);
        }
        civ.stats().invalidate();
        production.invalidate();
    }

    private void affiliate(Town town) {
        town.status(TownStatus.AFFILIATED);
        town.nativeCivId(town.civId());
        town.statusBeforeCapture(null);
        town.capturedAt(null);
        civ.state().save(town);
        Channels.global("town.capitulated", Messages.arg("town", town.name()));
    }

    /** Destroys all wonders of a town (gift, market). */
    public void destroyWonders(Town town) {
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (api == null) return;
        List<String> wonders = civ.balance().file("civ").getStringList("wonder-types");
        for (StructureApi.Placed p : List.copyOf(api.of(town))) {
            if (!wonders.contains(p.type().toLowerCase(Locale.ROOT))) continue;
            api.remove(p, false);
            new StructureDestroyedEvent(p.id(), p.type(), town.id(), StructureDestroyedEvent.Cause.DEMOLISHED).call();
        }
    }

    void civLog(Civilization c, String action, String name, String town) {
        CivModule civModule = civ.apiOrNull(CivModule.class);
        if (civModule != null) civModule.joinLog(c, action, name, town);
    }

    // --- listeners ------------------------------------------------------------------------------

    @EventHandler
    public void onCultureChanged(CultureChangedEvent e) {
        civ.stats().invalidate();
        production.invalidate();
    }

    /** The town centre follows the finished town hall/capitol (culture anchor, distances). */
    @EventHandler
    public void onStructureCompleted(StructureCompletedEvent e) {
        if (!service.isMainBuilding(e.type())) return;
        Town town = civ.state().town(e.townId());
        StructureApi api = civ.apiOrNull(StructureApi.class);
        if (town == null || api == null) return;
        StructureApi.Placed p = api.byId(e.structureId());
        if (p != null && !Objects.equals(town.center(), p.center())) {
            town.center(p.center());
            civ.state().save(town);
            civ.culture().recompute(true);
        }
        civ.stats().invalidate();
        production.invalidate();
    }

    /** Lava only inside the culture of one's own town (spec §6.4, §9.1). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (e.getBucket() != Material.LAVA_BUCKET) return;
        if (!civ.balance().file("town").getBoolean("lava-only-own-culture", true)) return;
        if (!civ.settings().isGameWorld(e.getBlock().getWorld())) return;
        if (e.getPlayer().hasPermission("civcraft.admin.bypass")) return;
        Town owner = civ.culture().owner(ChunkKey.of(e.getBlock()));
        Town mine = civ.state().townOf(e.getPlayer());
        if (owner == null || mine == null || !owner.id().equals(mine.id())) {
            e.setCancelled(true);
            civ.messages().actionBar(e.getPlayer(), "town.lava-denied");
        }
    }

    /** Border crossing notices (spec §9.1). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if ((e.getFrom().getBlockX() >> 4) == (e.getTo().getBlockX() >> 4)
                && (e.getFrom().getBlockZ() >> 4) == (e.getTo().getBlockZ() >> 4)
                && e.getFrom().getWorld().equals(e.getTo().getWorld())) return;
        Player p = e.getPlayer();
        Town now = civ.culture().owner(ChunkKey.of(e.getTo()));
        String nowId = now == null ? null : now.id();
        String before = lastCultureTown.put(p.getUniqueId(), nowId);
        if (Objects.equals(before, nowId)) return;
        if (now == null) {
            Town old = civ.state().town(before);
            if (old != null) civ.messages().actionBar(p, "town.border.leave", Messages.arg("town", old.name()));
            return;
        }
        Civilization owner = civ.state().civOf(now);
        Civilization mine = civ.state().civOf(p);
        String tag;
        if (now.outlaws().contains(p.getUniqueId())) tag = "outlaw";
        else if (owner != null && mine != null && owner.id().equals(mine.id())) tag = "own";
        else {
            DiplomacyApi dip = civ.apiOrNull(DiplomacyApi.class);
            RelationType rel = dip == null || owner == null || mine == null ? RelationType.NEUTRAL : dip.relation(owner.id(), mine.id());
            tag = rel.name().toLowerCase(Locale.ROOT);
        }
        civ.messages().send(p, "town.border.enter", Messages.arg("town", now.name()),
                Messages.arg("civ", owner == null ? "-" : owner.name()),
                Messages.arg("status", civ.messages().plain("town.border.status." + tag)));
        if (tag.equals("war") || tag.equals("outlaw") || tag.equals("hostile")) {
            String key = p.getUniqueId() + ":" + now.id();
            Instant last = borderNotified.get(key);
            if (last == null || last.plusSeconds(civ.balance().getInt("town", "border-alert-cooldown-seconds", 120)).isBefore(Instant.now())) {
                borderNotified.put(key, Instant.now());
                Channels.town(now, "town.border.alert", Messages.arg("name", p.getName()),
                        Messages.arg("status", civ.messages().plain("town.border.status." + tag)));
            }
        }
    }

    /**
     * Newbie protection (spec §1): a PvP-protected player cannot build, break or pour liquids inside
     * the culture of a foreign civilization.
     */
    private final class CultureGuard implements Guard {
        @Override
        public int priority() {
            return 450;
        }

        @Override
        public Verdict check(Player actor, Action action, Block block, Block source) {
            if (actor == null || (action != Action.BREAK && action != Action.PLACE && action != Action.ITEMUSE)) return Verdict.PASS;
            Resident r = civ.state().resident(actor);
            if (r == null || !r.isPvpProtected()) return Verdict.PASS;
            Town owner = civ.culture().owner(ChunkKey.of(block));
            if (owner == null) return Verdict.PASS;
            Town mine = civ.state().townOf(r);
            if (mine != null && Objects.equals(mine.civId(), owner.civId())) return Verdict.PASS;
            return Verdict.deny("protection.newbie");
        }
    }
}
