package com.civcraft.structure;

import static com.civcraft.core.text.Messages.arg;
import static com.civcraft.core.text.Messages.money;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.clock.GameClock;
import com.civcraft.command.AdminRegistry;
import com.civcraft.core.CivException;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Money;
import com.civcraft.event.StructureCompletedEvent;
import com.civcraft.event.StructureDestroyedEvent;
import com.civcraft.item.ItemApi;
import com.civcraft.model.Civilization;
import com.civcraft.model.Claim;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.storage.Stored;
import com.civcraft.structure.command.BuildCommand;
import com.civcraft.structure.command.StructureAdminCommand;

import com.civcraft.structure.component.MarkerHandler;
import com.civcraft.structure.component.StructureComponent;
import com.civcraft.structure.construction.ChangeJob;
import com.civcraft.structure.construction.UndoBuffer;
import com.civcraft.structure.construction.UndoStore;
import com.civcraft.structure.construction.WorldJobs;

import com.civcraft.structure.event.ConstructionCancelledEvent;
import com.civcraft.structure.event.StructureDamageEvent;
import com.civcraft.structure.event.StructureLevelChangedEvent;
import com.civcraft.structure.event.StructurePlacedEvent;
import com.civcraft.structure.event.StructureRepairedEvent;
import com.civcraft.structure.placement.Orientation;
import com.civcraft.structure.template.StructureTemplates;
import com.civcraft.structure.type.ClaimRule;
import com.civcraft.structure.type.Requirement;
import com.civcraft.structure.type.StructureType;
import com.civcraft.structure.type.StructureTypes;
import com.civcraft.template.Template;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;

/**
 * The structure framework (spec 02 §1, §2, §5; 01 §5.5, §6.2; 03 §4): types, placement and validation, progressive
 * construction, protection, components, behaviours, town hall and capitol, control blocks, repair, demolition,
 * upkeep, score and static effects.
 * <p>
 * Other modules plug in through {@link #registerBehavior}, {@link #registerMarkerHandler},
 * {@link #addAvailabilityGate}, {@link #addFootprintGate}, {@link #registerCustomPlacement} and
 * {@link #setWarActive}; the war module uses {@link #controlPoints()}, {@link #damage} and {@link #destroy}.
 */
public final class StructureModule implements Module, StructureApi {

    public static final String COLLECTION = "structures";
    public static final String ARCHITECTS = "structure_architects";
    public static final String RESTORES = "structure_restores";
    public static final String TOWN_HALL = "town_hall";
    public static final String CAPITOL = "capitol";

    private static final StructureBehavior NOOP = new StructureBehavior() {
    };

    /** Architects' experience of a civilization: wonder id → hammer discount 0..0.5 (spec 02 §1.11). */
    static final class ArchitectsRecord implements Stored {
        private String civId;
        private Map<String, Double> bonus = new HashMap<>();

        private ArchitectsRecord() {
        }

        ArchitectsRecord(String civId) {
            this.civId = civId;
        }

        @Override
        public String storageId() {
            return civId;
        }
    }

    /** A terrain restoration that must survive a restart. */
    static final class PendingRestore implements Stored {
        private String id;

        private PendingRestore() {
        }

        PendingRestore(String id) {
            this.id = id;
        }

        @Override
        public String storageId() {
            return id;
        }
    }

    private CivCraft civ;
    private StructureTypes types;
    private StructureSettings settings;
    private final StructureIndex index = new StructureIndex();
    private StructureTemplates templates;
    private PlacementValidator validator;
    private Integrations integrations;
    private UndoStore undo;
    private WorldJobs jobs;
    private Constructions constructions;
    private ControlPointService controlPoints;
    private PlacementService placement;
    private final Map<String, MarkerHandler> markerHandlers = new HashMap<>();
    private final Map<String, StructureBehavior> behaviors = new HashMap<>();
    private final List<BiFunction<Town, String, Optional<String>>> availabilityGates = new ArrayList<>();
    private final List<FootprintGate> footprintGates = new ArrayList<>();
    private final Map<String, CustomPlacement> customPlacements = new HashMap<>();
    private BooleanSupplier warActive = () -> false;
    private final Map<String, ArchitectsRecord> architects = new HashMap<>();
    private final Map<String, PendingRestore> restores = new HashMap<>();
    private final Map<ChunkKey, List<Runnable>> deferred = new HashMap<>();
    private boolean startupDone;

    @Override
    public String id() {
        return "structure";
    }

    // =================================================================================================================
    // lifecycle
    // =================================================================================================================

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include("structure");
        types = new StructureTypes(civ.balance(), civ.logger());
        settings = StructureSettings.load(civ.balance());
        integrations = new Integrations(civ, settings.fallbackHammers());
        templates = new StructureTemplates(civ.templates(), civ.logger());
        validator = new PlacementValidator(this, civ);
        undo = new UndoStore(civ.plugin().getDataFolder(), civ.logger());
        jobs = new WorldJobs(civ.logger());
        for (String c : List.of(COLLECTION, ARCHITECTS, RESTORES)) civ.store().createCollection(c);
        int loaded = 0;
        for (Structure s : civ.store().loadAll(COLLECTION, Structure.class)) {
            if (loadStructure(s)) loaded++;
        }
        for (ArchitectsRecord r : civ.store().loadAll(ARCHITECTS, ArchitectsRecord.class)) {
            if (r.civId != null && r.bonus != null) architects.put(r.civId, r);
        }
        for (PendingRestore r : civ.store().loadAll(RESTORES, PendingRestore.class)) {
            if (r.id != null) restores.put(r.id, r);
        }
        civ.logger().info("Loaded " + loaded + " structures (" + types.all().size() + " types)");
    }

    private boolean loadStructure(Structure s) {
        if (s == null || s.id() == null || s.type() == null) return false;
        StructureType type = types.get(s.type());
        if (type == null) {
            civ.logger().warning("Structure " + s.id() + " has unknown type " + s.type() + "; it is ignored (document kept)");
            return false;
        }
        if (civ.state().town(s.townId()) == null) {
            civ.logger().warning("Structure " + s.id() + " (" + s.type() + ") belongs to missing town " + s.townId() + "; removed");
            civ.saves().delete(COLLECTION, s.id());
            return false;
        }
        s.typeDef(type);
        attachTemplate(s);
        index.add(s);
        if (s.isBuilding() && s.customBlocks() == null) {
            UndoBuffer buffer = undo.loadNow(s.id());
            if (buffer == null) buffer = newUndo(s);
            s.undo(buffer);
        }
        return true;
    }

    @Override
    public void enable(CivCraft civ) {
        controlPoints = new ControlPointService(this, civ);
        constructions = new Constructions(this, civ);
        placement = new PlacementService(this, civ);
        DefaultMarkers.register(this, civ);
        civ.protection().register(new StructureGuard(this, civ));
        civ.listen(new StructureListener(this, civ));
        civ.listen(placement);
        civ.stats().register(new StructureEffects(this, civ));
        civ.clock().everySecond("structure-construction", constructions::tickSecond);
        civ.clock().everySecond("structure-placement", placement::tick);
        civ.clock().hourly(GameClock.PRODUCTION, "structure-hourly", this::hourly);
        civ.clock().daily(GameClock.PRODUCTION, "structure-daily", this::daily);
        civ.tasks().timer(1, 1, constructions::tickBlocks);
        civ.tasks().timer(20L * 60 * 5, 20L * 60 * 5, this::flushUndo);

        BuildCommand build = new BuildCommand(this, civ);
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(build.node(), civ.messages().plain("structure.command.description"), List.of("b")));
        AdminRegistry.add(new StructureAdminCommand(this, civ).node());

        for (PendingRestore r : List.copyOf(restores.values())) resumeRestore(r.id);
        civ.tasks().nextTick(() -> {
            startupDone = true;
            for (Structure s : List.copyOf(index.all())) {
                if (s.removed()) continue;
                if (s.complete()) controlPoints.refresh(s);
                if (s.isActive() || s.isDestroyed()) safe(s, "onLoad", () -> behavior(s.type()).onLoad(s));
            }
        });
    }

    @Override
    public void disable(CivCraft civ) {
        if (placement != null) placement.clearAll();
        if (constructions != null) constructions.shutdown();
        if (undo == null) return;
        // Let pending asynchronous writes finish first so the final synchronous writes cannot race with them.
        undo.shutdown();
        for (Structure s : index.all()) {
            if (s.undo() != null && s.undo().dirty()) undo.saveNow(s.id(), s.undo());
            if (s.dirty()) civ.saves().save(COLLECTION, s);
        }
    }

    private void flushUndo() {
        for (Structure s : index.all()) {
            if (s.undo() != null && s.undo().dirty()) undo.saveAsync(s.id(), s.undo());
        }
    }

    // =================================================================================================================
    // plug-in points for other modules
    // =================================================================================================================

    /** Registers the behaviour of a structure type; onLoad runs for existing complete structures on the next tick. */
    public void registerBehavior(String type, StructureBehavior behavior) {
        behaviors.put(type.toLowerCase(Locale.ROOT), behavior);
        // Before the first tick the startup pass calls onLoad for every structure; afterwards do it here.
        if (civ == null || !startupDone) return;
        civ.tasks().nextTick(() -> {
            for (Structure s : index.all()) {
                if (s.type().equals(type) && (s.isActive() || s.isDestroyed())) safe(s, "onLoad", () -> behavior.onLoad(s));
            }
        });
    }

    public StructureBehavior behavior(String type) {
        return behaviors.getOrDefault(type, NOOP);
    }

    public boolean hasBehavior(String type) {
        return behaviors.containsKey(type);
    }

    /** Registers (or replaces) the handler of a marker type ({@code chest}, {@code sign}, {@code control}...). */
    public void registerMarkerHandler(MarkerHandler handler) {
        markerHandlers.put(handler.type().toLowerCase(Locale.ROOT), handler);
    }

    public MarkerHandler markerHandler(String type) {
        return markerHandlers.get(type);
    }

    /**
     * Adds a gate consulted for every type a town may build (GUI and placement). Returns a message key of the reason
     * when the type is unavailable. The science module uses it for tech checks, the war module for war rules.
     */
    public void addAvailabilityGate(BiFunction<Town, String, Optional<String>> gate) {
        availabilityGates.add(gate);
    }

    List<BiFunction<Town, String, Optional<String>>> availabilityGates() {
        return availabilityGates;
    }

    public void addFootprintGate(FootprintGate gate) {
        footprintGates.add(gate);
    }

    List<FootprintGate> footprintGates() {
        return footprintGates;
    }

    /** Construction flow for a category ({@code wall}, {@code road}) or a single type id. */
    public void registerCustomPlacement(String categoryOrType, CustomPlacement flow) {
        customPlacements.put(categoryOrType.toLowerCase(Locale.ROOT), flow);
    }

    CustomPlacement customPlacement(StructureType type) {
        CustomPlacement p = customPlacements.get(type.id());
        return p != null ? p : customPlacements.get(type.category().key());
    }

    /** War state provider: /build is disabled and war-regeneration rules apply while it returns true. */
    public void setWarActive(BooleanSupplier supplier) {
        this.warActive = supplier == null ? () -> false : supplier;
    }

    public boolean warActive() {
        try {
            return warActive.getAsBoolean();
        } catch (RuntimeException e) {
            civ.logger().log(Level.WARNING, "War state provider failed", e);
            return false;
        }
    }

    // =================================================================================================================
    // accessors
    // =================================================================================================================

    public StructureTypes types() {
        return types;
    }

    StructureSettings settings() {
        return settings;
    }

    StructureIndex index() {
        return index;
    }

    StructureTemplates templates() {
        return templates;
    }

    PlacementValidator validator() {
        return validator;
    }

    Integrations integrations() {
        return integrations;
    }

    WorldJobs jobs() {
        return jobs;
    }

    public ControlPointService controlPoints() {
        return controlPoints;
    }

    public PlacementService placement() {
        return placement;
    }

    public Collection<Structure> all() {
        return index.all();
    }

    public List<Structure> structures(Town town) {
        return index.town(town.id());
    }

    public Structure structure(String id) {
        return index.byId(id);
    }

    public Structure structureAt(BlockPos pos) {
        return index.at(pos);
    }

    public StructureComponent componentAt(BlockPos pos) {
        return index.component(pos);
    }

    public Town town(Structure s) {
        return civ.state().town(s.townId());
    }

    /** Theme names by id for menus and tab completion. */
    public Map<String, String> themes() {
        return settings.themes();
    }

    public String defaultTheme(Town town) {
        if (town != null && settings.themeExists(town.theme())) return town.theme().toLowerCase(Locale.ROOT);
        String def = civ.settings().defaultTheme();
        return settings.themeExists(def) ? def.toLowerCase(Locale.ROOT) : "default";
    }

    /** Sets the default theme of a town (validated). */
    public void setDefaultTheme(Town town, String theme) throws CivException {
        CivException.check(settings.themeExists(theme), "structure.error.unknown-theme", arg("theme", theme));
        town.theme(theme.toLowerCase(Locale.ROOT));
        civ.state().save(town);
    }

    public void save(Structure s) {
        Tasks.checkMain();
        if (s.removed()) return;
        s.dirty(false);
        civ.saves().save(COLLECTION, s);
    }

    /** Runs {@code task} now if the structure's origin chunk is loaded, else when it loads. Never loads chunks. */
    public void whenLoaded(Structure s, Runnable task) {
        BlockPos o = s.origin();
        World w = o.bukkitWorld();
        if (w != null && w.isChunkLoaded(o.x() >> 4, o.z() >> 4)) {
            task.run();
            return;
        }
        deferred.computeIfAbsent(o.chunk(), k -> new ArrayList<>()).add(task);
    }

    void runDeferred(ChunkKey chunk) {
        List<Runnable> tasks = deferred.remove(chunk);
        if (tasks == null) return;
        for (Runnable r : tasks) {
            try {
                r.run();
            } catch (RuntimeException e) {
                civ.logger().log(Level.SEVERE, "Deferred structure task failed", e);
            }
        }
    }

    void safe(Structure s, String hook, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            civ.logger().log(Level.SEVERE, "Structure behaviour " + s.type() + "." + hook + " failed for " + s.id(), e);
        }
    }

    // =================================================================================================================
    // queries used by validation, commands and other modules
    // =================================================================================================================

    /** The working main building (capitol preferred, then town hall), or null. */
    public Structure mainBuilding(Town town) {
        Structure hall = null;
        for (Structure s : index.town(town.id())) {
            if (s.isBuilding() || s.removed()) continue;
            if (s.type().equals(CAPITOL)) return s;
            if (s.type().equals(TOWN_HALL)) hall = s;
        }
        return hall;
    }

    /**
     * Respawn points of the town for the war module: {@code /respawn} and {@code /revive} markers of the working main
     * building (spec 01 §5.5 "военная комната (точка респавна на войне)").
     */
    public List<BlockPos> respawnPoints(Town town) {
        List<BlockPos> points = new ArrayList<>();
        Structure main = mainBuilding(town);
        if (main == null) return points;
        for (StructureComponent c : main.components()) {
            if (c.type().equals("respawn") || c.type().equals("revive")) points.add(c.pos());
        }
        return points;
    }

    /**
     * The war room of the town: the {@code /warroom} marker of a working military base (capital only, spec 02 §3.30),
     * else of the main building, else its first {@code /revive} or {@code /respawn} point.
     */
    public Optional<BlockPos> warRoom(Town town) {
        for (Structure s : index.town(town.id())) {
            if (s.type().equals("military_base") && s.isActive()) {
                for (StructureComponent c : s.components("warroom")) return Optional.of(c.pos());
            }
        }
        Structure main = mainBuilding(town);
        if (main == null) return Optional.empty();
        for (String marker : List.of("warroom", "revive", "respawn")) {
            List<StructureComponent> list = main.components(marker);
            if (!list.isEmpty()) return Optional.of(list.getFirst().pos());
        }
        return Optional.of(main.center());
    }

    /** The ordinary building under construction in the town (walls and roads excluded), or null. */
    public Structure buildingNormal(Town town) {
        for (Structure s : index.town(town.id())) {
            if (s.isBuilding() && !s.typeDef().isWonder() && !s.typeDef().category().isCustom()) return s;
        }
        return null;
    }

    public Structure buildingWonder(Town town) {
        for (Structure s : index.town(town.id())) {
            if (s.isBuilding() && s.typeDef().isWonder()) return s;
        }
        return null;
    }

    /** Whether the town has a complete, working structure of one of the ids with at least the level. */
    public boolean hasWorking(Town town, Collection<String> typeIds, int level) {
        for (Structure s : index.town(town.id())) {
            if (s.state() == Structure.State.COMPLETE && typeIds.contains(s.type()) && (level <= 0 || s.level() >= level)) return true;
        }
        return false;
    }

    /** Structures of the type's limit group in the town (any state). */
    public int countInTown(Town town, StructureType type) {
        String key = type.limitKey();
        int n = 0;
        for (Structure s : index.town(town.id())) {
            if (s.removed() || !s.typeDef().limitKey().equals(key)) continue;
            // Wonder ruins do not block building the wonder again (spec 03 §4.6: destroyed wonders are rebuilt anew).
            if (s.isDestroyed() && s.typeDef().isWonder()) continue;
            n++;
        }
        return n;
    }

    public int slotsUsed(Town town) {
        int n = 0;
        for (Structure s : index.town(town.id())) if (!s.removed() && s.typeDef().slot()) n++;
        return n;
    }

    public int slots(Town town) {
        return integrations.slots(town);
    }

    /** Hammer discount from architects' experience for a wonder (0..max). */
    public double architectsBonus(String civId, String wonder) {
        ArchitectsRecord r = architects.get(civId);
        if (r == null) return 0;
        return Math.max(0, Math.min(settings.architectsMax(), r.bonus.getOrDefault(wonder, 0.0)));
    }

    public Map<String, Double> architectsBonuses(String civId) {
        ArchitectsRecord r = architects.get(civId);
        return r == null ? Map.of() : Map.copyOf(r.bonus);
    }

    /** Price after modifiers, hundredths. */
    public long price(Town town, StructureType type) {
        return validator.cost(town, type, type.isMain() && mainBuilding(town) != null);
    }

    /** Hammers after modifiers and architects' experience. */
    public double requiredHammers(Town town, StructureType type) {
        return validator.hammers(town, type);
    }

    /** Hammers per hour spent on construction (spec 02 §1.4: 2 × town hammers). */
    public double buildRate(Town town) {
        return settings.speedMultiplier() * integrations.hammersPerHour(town);
    }

    /** The town the player manages (selected town, else own town). */
    public Town selectedTown(Player player) throws CivException {
        return integrations.selectedTown(player);
    }

    /** Mayor, assistant or civ leader acting for the town. */
    public boolean canManage(Player player, Town town) {
        return integrations.canManage(player, town);
    }

    /** {@code /civ perm} check (demolish...). */
    public void checkCivPerm(Player player, Civilization c, String perm) throws CivException {
        integrations.checkCivPerm(player, c, perm);
    }

    public double hammersPerHour(Town town) {
        return integrations.hammersPerHour(town);
    }

    public String techName(String tech) {
        return integrations.techName(tech);
    }

    /** Seconds until the structure's construction or repair finishes, -1 without production. */
    public long secondsRemaining(Structure s) {
        Town town = town(s);
        if (town == null) return -1;
        double hph = integrations.hammersPerHour(town);
        if (s.repairing()) {
            int parallel = 0;
            for (Structure o : index.town(town.id())) if (o.repairing()) parallel++;
            double rate = com.civcraft.structure.construction.BuildMath.repairPerSecond(hph, parallel);
            return rate <= 0 ? -1 : (long) Math.ceil(Math.max(0, s.repairRequired() - s.repairDone()) / rate);
        }
        return com.civcraft.structure.construction.BuildMath.secondsRemaining(s.hammersRequired() - s.hammersDone(), hph,
                settings.speedMultiplier());
    }

    public double confirmRadius() {
        return settings.confirmRadius();
    }

    /** Throws the first reason why the town cannot build the type now (GUI tooltips, /build info). */
    public void checkAvailable(Town town, StructureType type) throws CivException {
        validator.checkAvailable(town, type);
    }

    // =================================================================================================================
    // StructureApi
    // =================================================================================================================

    @Override
    public Placed at(BlockPos pos) {
        return index.at(pos);
    }

    @Override
    public List<? extends Placed> of(Town town) {
        return index.town(town.id());
    }

    @Override
    public List<? extends Placed> of(Town town, String type) {
        List<Structure> result = new ArrayList<>();
        for (Structure s : index.town(town.id())) if (s.type().equals(type)) result.add(s);
        return result;
    }

    @Override
    public Placed byId(String id) {
        return index.byId(id);
    }

    @Override
    public boolean typeExists(String type) {
        return types.exists(type);
    }

    @Override
    public Placed place(Player player, Town town, String typeId, BlockPos origin, StructureRotation rotation, String theme,
                        boolean instant, boolean skipChecks) throws CivException {
        Tasks.checkMain();
        StructureType type = types.get(typeId);
        if (type == null) throw new CivException("structure.error.unknown-type", arg("name", typeId));
        CivException.check(type.usesTemplate(), "structure.error.custom-unavailable");
        String th = theme != null && settings.themeExists(theme) ? theme.toLowerCase(Locale.ROOT) : defaultTheme(town);
        StructureRotation rot = rotation == null ? StructureRotation.NONE : rotation;
        StructureTemplates.Resolved res = templates.resolve(type, th);
        Template t = templates.rotate(res.template(), rot);
        World world = origin.bukkitWorld();
        CivException.check(world != null, "structure.error.world");
        PlacementValidator.Site site = new PlacementValidator.Site(world, origin, t.sizeX(), t.sizeY(), t.sizeZ());
        return startConstruction(town, type, site, rot, th, res, player, instant, skipChecks);
    }

    @Override
    public void remove(Placed placed, boolean restoreTerrain) {
        Structure s = index.byId(placed.id());
        if (s == null) return;
        boolean townGone = civ.state().town(s.townId()) == null;
        if (s.isBuilding()) {
            cancel(s, townGone ? ConstructionCancelledEvent.Reason.DISBANDED : ConstructionCancelledEvent.Reason.CANCELLED, restoreTerrain, 0);
        } else {
            remove(s, townGone ? StructureDestroyedEvent.Cause.DISBANDED : StructureDestroyedEvent.Cause.DEMOLISHED, restoreTerrain);
        }
    }

    @Override
    public Placement placementFor(Player player, String typeId, int yOffset) {
        StructureType type = types.get(typeId);
        if (type == null) throw new IllegalArgumentException("Unknown structure type " + typeId);
        Town town = civ.state().townOf(player);
        return computePlacement(player.getLocation(), type, defaultTheme(town), null, yOffset);
    }

    /** Origin and rotation for a structure built by someone standing at {@code loc} (spec 02 §1.3). */
    public Placement computePlacement(Location loc, StructureType type, String theme, Integer absoluteY, int yOffset) {
        BlockFace facing = Orientation.facing(loc.getYaw());
        StructureRotation rot = Orientation.rotation(facing);
        StructureTemplates.Resolved res = templates.resolve(type, theme);
        Template t = templates.rotate(res.template(), rot);
        Orientation.ChunkRect rect = Orientation.footprint(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, facing,
                type.chunksX(), type.chunksZ());
        int ox = (rect.minX() << 4) + Orientation.centreOffset(rect.sizeX() << 4, t.sizeX());
        int oz = (rect.minZ() << 4) + Orientation.centreOffset(rect.sizeZ() << 4, t.sizeZ());
        World world = loc.getWorld();
        int baseY = type.water().isWater() ? world.getSeaLevel() - 1
                : (absoluteY != null ? absoluteY : loc.getBlockY() + yOffset);
        return new Placement(new BlockPos(world.getName(), ox, baseY + type.yShift(), oz), rot, t.sizeX(), t.sizeY(), t.sizeZ());
    }

    // =================================================================================================================
    // construction lifecycle
    // =================================================================================================================

    /**
     * Validates, charges and starts construction. All checks run first; nothing is changed (money, claims, blocks)
     * unless every check passed.
     */
    Structure startConstruction(Town town, StructureType type, PlacementValidator.Site site, StructureRotation rot,
                                String theme, StructureTemplates.Resolved res, Player player, boolean instant,
                                boolean skipChecks) throws CivException {
        Tasks.checkMain();
        Structure currentMain = mainBuilding(town);
        boolean replaces = type.isMain() && currentMain != null;
        // A town that is being founded may not be in the culture map yet.
        if (type.isMain() && currentMain == null) civ.culture().invalidate();
        long cost = 0;
        ItemApi items = integrations.items();
        boolean takeItem = false;
        if (!skipChecks) {
            validator.checkAvailable(town, type);
            validator.checkSite(town, type, site, null);
            behavior(type.id()).checkBuild(town, player);
            cost = validator.cost(town, type, replaces);
            if (town.treasury() < cost) throw new CivException("structure.error.money", money("cost", cost));
            if (type.requiredItem() != null && items != null && items.exists(type.requiredItem())) {
                if (player == null || items.count(player, type.requiredItem()) < 1) {
                    throw new CivException("structure.error.item", arg("item", items.displayName(type.requiredItem())));
                }
                takeItem = true;
            }
        }
        // --- everything is valid: apply --------------------------------------------------------------------------
        if (takeItem && !items.take(player, type.requiredItem(), 1)) {
            throw new CivException("structure.error.item", arg("item", items.displayName(type.requiredItem())));
        }
        if (cost > 0) {
            town.addTreasury(-cost);
            civ.state().save(town);
        }
        Template rotated = templates.rotate(res.template(), rot);
        Structure s = new Structure(newId(), type, town.id(), site.origin(), rot, theme, res.templateId(), res.procedural(),
                rotated.sizeX(), rotated.sizeY(), rotated.sizeZ());
        s.template(rotated, templates.solidMask(rotated));
        s.components(buildComponents(s, rotated));
        s.paidCost(cost);
        s.hammersRequired(skipChecks ? type.hammers() : validator.hammers(town, type));
        s.replacesMain(replaces);
        int hp = computeMaxHp(s, town);
        s.maxHp(hp);
        s.hp(hp);
        s.undo(newUndo(s));
        claimFor(s, town, type);
        index.add(s);
        save(s);
        safe(s, "onPlaced", () -> behavior(type.id()).onPlaced(s));
        new StructurePlacedEvent(s.id(), s.type(), town.id()).call();
        TagResolver[] args = {arg("name", type.name()), arg("town", town.name()), coords(s)};
        if (type.isWorldWonder()) broadcast("structure.wonder.started", args);
        else tellTown(town, "structure.started", args);
        if (instant || s.hammersRequired() <= 0) {
            s.hammersDone(s.hammersRequired());
            complete(s);
            constructions.pasteNow(s);
        }
        return s;
    }

    private String newId() {
        String id;
        do {
            id = Long.toHexString(ThreadLocalRandom.current().nextLong(0x10000000L, 0xFFFFFFFFL));
        } while (index.byId(id) != null || restores.containsKey(id));
        return id;
    }

    private UndoBuffer newUndo(Structure s) {
        return new UndoBuffer(s.origin().world(), s.origin().x(), s.origin().y(), s.origin().z(), s.sizeX(), s.sizeY(), s.sizeZ());
    }

    private void claimFor(Structure s, Town town, StructureType type) {
        if (type.claim() != ClaimRule.AUTO) return;
        for (ChunkKey k : s.chunks()) {
            Claim c = civ.state().claim(k);
            if (c == null) {
                c = new Claim(k, town.id());
                c.locked(true);
                civ.state().addClaim(c);
                s.autoClaims().add(k);
            } else if (c.townId().equals(town.id())) {
                if (!c.locked()) {
                    c.locked(true);
                    civ.state().save(c);
                }
            } else {
                continue;
            }
            s.lockedClaims().add(k);
        }
    }

    void attachTemplate(Structure s) {
        if (s.customBlocks() != null) {
            s.components(List.of());
            return;
        }
        StructureType type = s.typeDef();
        Template base = templates.load(type, s.theme(), s.templateId(), s.procedural());
        Template rotated = templates.rotate(base, s.rotation());
        if (rotated.sizeX() != s.sizeX() || rotated.sizeY() != s.sizeY() || rotated.sizeZ() != s.sizeZ()) {
            civ.logger().warning("Template of structure " + s.id() + " (" + s.type() + ") changed size; the whole volume is protected"
                    + " and blocks are not re-pasted");
            s.template(null, null);
            s.components(List.of());
            return;
        }
        s.template(rotated, templates.solidMask(rotated));
        s.components(buildComponents(s, rotated));
    }

    private List<StructureComponent> buildComponents(Structure s, Template t) {
        List<StructureComponent> list = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        BlockFace front = Orientation.frontFace(s.rotation());
        for (Template.Marker m : t.markers()) {
            BlockFace facing = front;
            if (m.facing() != null) {
                try {
                    facing = BlockFace.valueOf(m.facing().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    facing = front;
                }
            }
            int n = counters.merge(m.type(), 1, Integer::sum) - 1;
            list.add(new StructureComponent(s.id(), m.type(), m.args(), s.origin().offset(m.x(), m.y(), m.z()), facing, n));
        }
        return list;
    }

    /** Max HP: type HP × (1 + structure_hp modifiers), capped for walls. */
    int computeMaxHp(Structure s, Town town) {
        StructureType type = s.typeDef();
        if (type.hp() <= 0) return 0;
        double percent = 0;
        if (town != null) {
            percent += civ.stats().town(town).percent("structure_hp");
            for (String tag : type.tags()) percent += civ.stats().town(town).percent("structure_hp." + tag);
            percent += civ.stats().town(town).percent("structure_hp." + type.category().key());
        }
        int hp = (int) Math.round(type.hp() * Math.max(0.1, 1 + percent));
        return type.hpCap() > 0 ? Math.min(type.hpCap(), hp) : hp;
    }

    /** Hammers are complete: the structure starts working. Remaining blocks keep pasting as chunks are loaded. */
    void complete(Structure s) {
        Town town = town(s);
        StructureType type = s.typeDef();
        s.state(Structure.State.COMPLETE);
        s.completedAt(Instant.now());
        s.hammersDone(s.hammersRequired());
        int hp = computeMaxHp(s, town);
        s.maxHp(hp);
        s.hp(hp);
        if (type.control() != null) controlPoints.create(s);
        save(s);
        constructions.forget(s);
        if (town == null) return;
        if (type.isWorldWonder()) resolveWonderRace(s, town);
        if (s.replacesMain()) {
            for (Structure old : List.copyOf(index.town(town.id()))) {
                if (old != s && old.typeDef().isMain() && !old.isBuilding()) remove(old, StructureDestroyedEvent.Cause.REPLACED, true);
            }
        }
        if (type.isMain()) {
            BlockPos c = s.center();
            town.center(new BlockPos(c.world(), c.x(), s.origin().y(), c.z()));
            civ.state().save(town);
            if (type.id().equals(CAPITOL)) {
                Civilization cv = civ.state().civOf(town);
                if (cv != null) {
                    if (cv.capitalId() == null) cv.capitalId(town.id());
                    if (cv.isProvince()) cv.province(false);
                    civ.state().save(cv);
                }
            }
            civ.culture().recompute(true);
        }
        safe(s, "onComplete", () -> behavior(s.type()).onComplete(s));
        civ.stats().invalidate();
        new StructureCompletedEvent(s.id(), s.type(), town.id()).call();
        TagResolver[] args = {arg("name", type.name()), arg("town", town.name()), coords(s)};
        if (type.isWorldWonder()) broadcast("structure.wonder.completed", args);
        else tellTown(town, "structure.completed", args);
    }

    /** All blocks and markers are in place (after completion, repair or refresh). */
    void blocksReady(Structure s) {
        if (s.removed()) return;
        s.markersBuilt(true);
        for (StructureComponent c : s.components()) buildMarker(s, c);
        controlPoints.refresh(s);
        if (s.undo() != null) {
            undo.saveAsync(s.id(), s.undo());
            s.undo(null);
        }
        save(s);
        safe(s, "onBlocksReady", () -> behavior(s.type()).onBlocksReady(s));
    }

    void buildMarker(Structure s, StructureComponent c) {
        MarkerHandler h = markerHandlers.get(c.type());
        if (h == null) return;
        var block = c.block();
        if (block == null) {
            whenLoaded(s, () -> {
                var b = c.block();
                if (b != null && !s.removed()) safe(s, "marker " + c.type(), () -> h.build(s, c, b));
            });
            return;
        }
        safe(s, "marker " + c.type(), () -> h.build(s, c, block));
    }

    private void resolveWonderRace(Structure winner, Town town) {
        Civilization winnerCiv = civ.state().civOf(town);
        StructureType type = winner.typeDef();
        for (Structure other : List.copyOf(index.all())) {
            if (other == winner || !other.type().equals(type.id()) || !other.isBuilding()) continue;
            Town loser = town(other);
            long refund = Money.multiply(other.paidCost(), settings.raceRefund());
            if (loser != null) {
                if (refund > 0) {
                    loser.addTreasury(refund);
                    civ.state().save(loser);
                }
                Civilization loserCiv = civ.state().civOf(loser);
                if (loserCiv != null && (winnerCiv == null || !loserCiv.id().equals(winnerCiv.id()))) {
                    double bonus = com.civcraft.structure.construction.BuildMath.architectsDiscount(other.progress(),
                            settings.architectsPer2Percent(), settings.architectsMax());
                    recordArchitects(loserCiv.id(), type.id(), bonus);
                }
                tellTown(loser, "structure.wonder.lost", arg("name", type.name()), arg("town", town.name()), money("refund", refund));
            }
            cancel(other, ConstructionCancelledEvent.Reason.WONDER_RACE_LOST, true, 0);
        }
        if (winnerCiv != null) {
            ArchitectsRecord r = architects.get(winnerCiv.id());
            if (r != null && r.bonus.remove(type.id()) != null) civ.saves().save(ARCHITECTS, r);
        }
    }

    private void recordArchitects(String civId, String wonder, double bonus) {
        if (bonus <= 0) return;
        ArchitectsRecord r = architects.computeIfAbsent(civId, ArchitectsRecord::new);
        double old = r.bonus.getOrDefault(wonder, 0.0);
        if (bonus > old) {
            r.bonus.put(wonder, bonus);
            civ.saves().save(ARCHITECTS, r);
        }
    }

    // =================================================================================================================
    // cancel / remove / destroy / repair / refresh / level
    // =================================================================================================================

    /** Cancels construction by a player (spec: "/build cancel"); refunds the configured share. */
    public void cancelByPlayer(Structure s) {
        if (!s.isBuilding() || s.removed()) return;
        long refund = Money.multiply(s.paidCost(), settings.cancelRefund());
        cancel(s, ConstructionCancelledEvent.Reason.CANCELLED, true, refund);
    }

    /** Removes a structure that never completed; auto-claimed chunks are released. */
    void cancel(Structure s, ConstructionCancelledEvent.Reason reason, boolean restoreTerrain, long refund) {
        if (s.removed()) return;
        Town town = town(s);
        if (refund > 0 && town != null) {
            town.addTreasury(refund);
            civ.state().save(town);
        }
        detach(s, true, restoreTerrain);
        new ConstructionCancelledEvent(s.id(), s.type(), s.townId(), reason).call();
        if (town != null && reason == ConstructionCancelledEvent.Reason.CANCELLED) {
            tellTown(town, "structure.cancelled", arg("name", s.typeDef().name()), money("refund", refund));
        }
    }

    /** Removes a completed (or destroyed) structure for good. */
    public void remove(Structure s, StructureDestroyedEvent.Cause cause, boolean restoreTerrain) {
        Tasks.checkMain();
        if (s.removed()) return;
        if (s.isBuilding()) {
            cancel(s, cause == StructureDestroyedEvent.Cause.DISBANDED ? ConstructionCancelledEvent.Reason.DISBANDED
                    : ConstructionCancelledEvent.Reason.ADMIN, restoreTerrain, 0);
            return;
        }
        safe(s, "onRemoved", () -> behavior(s.type()).onRemoved(s, cause));
        detach(s, false, restoreTerrain);
        new StructureDestroyedEvent(s.id(), s.type(), s.townId(), cause).call();
    }

    private void detach(Structure s, boolean releaseAutoClaims, boolean restoreTerrain) {
        s.markRemoved();
        for (StructureComponent c : s.components()) {
            MarkerHandler h = markerHandlers.get(c.type());
            if (h != null) safe(s, "marker remove " + c.type(), () -> h.remove(s, c));
        }
        controlPoints.forget(s);
        constructions.forget(s);
        index.remove(s);
        civ.saves().delete(COLLECTION, s.id());
        for (ChunkKey k : s.lockedClaims()) {
            Claim c = civ.state().claim(k);
            if (c == null || !c.townId().equals(s.townId())) continue;
            if (releaseAutoClaims && s.autoClaims().contains(k) && !lockedByOther(k, s)) {
                civ.state().removeClaim(c);
            } else if (!lockedByOther(k, s)) {
                c.locked(false);
                civ.state().save(c);
            }
        }
        if (restoreTerrain) restoreTerrain(s);
        else undo.delete(s.id());
        civ.stats().invalidate();
    }

    private boolean lockedByOther(ChunkKey k, Structure except) {
        for (Structure o : index.chunk(k)) if (o != except && o.lockedClaims().contains(k)) return true;
        return false;
    }

    private void restoreTerrain(Structure s) {
        UndoBuffer buffer = s.undo();
        s.undo(null);
        if (buffer != null) {
            undo.saveAsync(s.id(), buffer);
            startRestore(s.id(), buffer);
        } else if (undo.exists(s.id())) {
            PendingRestore doc = new PendingRestore(s.id());
            restores.put(s.id(), doc);
            civ.saves().save(RESTORES, doc);
            resumeRestore(s.id());
        }
    }

    private void resumeRestore(String id) {
        undo.loadAsync(id).thenAccept(buffer -> civ.tasks().sync(() -> {
            if (buffer == null) {
                restores.remove(id);
                civ.saves().delete(RESTORES, id);
                return;
            }
            startRestore(id, buffer);
        }));
    }

    private void startRestore(String id, UndoBuffer buffer) {
        if (!restores.containsKey(id)) {
            PendingRestore doc = new PendingRestore(id);
            restores.put(id, doc);
            civ.saves().save(RESTORES, doc);
        }
        ChangeJob job = new ChangeJob(buffer.world(), () -> {
            restores.remove(id);
            civ.saves().delete(RESTORES, id);
            undo.delete(id);
        });
        for (int i = 0; i < buffer.size(); i++) {
            int[] p = buffer.position(buffer.cell(i));
            BlockData data;
            try {
                data = Bukkit.createBlockData(buffer.value(i));
            } catch (IllegalArgumentException e) {
                data = Bukkit.createBlockData(org.bukkit.Material.AIR);
            }
            job.add(p[0], p[1], p[2], data);
        }
        jobs.add(job);
    }

    /**
     * Damages a structure (war hits, TNT, cannons – called by the war module). Incomplete structures cannot be damaged
     * except wonders. Returns the remaining HP.
     */
    public int damage(Structure s, int amount, Player attacker) {
        Tasks.checkMain();
        if (s.removed() || s.isDestroyed() || amount <= 0 || s.maxHp() <= 0) return s.hp();
        if (s.isBuilding() && !s.typeDef().isWonder()) return s.hp();
        Town town = town(s);
        StructureDamageEvent event = new StructureDamageEvent(s.id(), s.type(), s.townId(), attacker, amount).call();
        if (event.isCancelled() || event.amount() <= 0) return s.hp();
        int before = s.hp();
        s.hp(before - event.amount());
        s.dirty(true);
        int after = s.hp();
        safe(s, "onDamaged", () -> behavior(s.type()).onDamaged(s, event.amount(), attacker));
        if (attacker != null) {
            civ.messages().actionBar(attacker, "structure.damage.attacker", arg("name", s.typeDef().name()),
                    arg("hp", after), arg("max", s.maxHp()));
        }
        int stepBefore = before * 10 / s.maxHp();
        int stepAfter = after * 10 / s.maxHp();
        if (stepAfter < stepBefore && town != null) {
            tellTown(town, "structure.damage.town", arg("name", s.typeDef().name()), arg("percent", stepAfter * 10), coords(s));
        }
        if (after <= 0) destroy(s, StructureDestroyedEvent.Cause.WAR);
        else constructions.markDamaged(s);
        return after;
    }

    /** Hit points reached zero, or an admin / capture destroyed it: ruins that give nothing until repaired. */
    public void destroy(Structure s, StructureDestroyedEvent.Cause cause) {
        Tasks.checkMain();
        if (s.removed() || s.isDestroyed()) return;
        Town town = town(s);
        StructureType type = s.typeDef();
        if (s.isBuilding()) {
            // A wonder destroyed during construction is lost without refund (legacy rule).
            if (town != null) tellTown(town, "structure.destroyed-building", arg("name", type.name()));
            cancel(s, ConstructionCancelledEvent.Reason.DESTROYED, false, 0);
            return;
        }
        s.state(Structure.State.DESTROYED);
        s.hp(0);
        s.destroyedAt(Instant.now());
        s.repairing(false);
        int minLevel = type.startLevel() == 0 ? 0 : 1;
        if (type.loseLevelOnDestroy() && s.level() > minLevel) setLevel(s, s.level() - 1);
        save(s);
        safe(s, "onDestroyed", () -> behavior(s.type()).onDestroyed(s, cause));
        civ.stats().invalidate();
        new StructureDestroyedEvent(s.id(), s.type(), s.townId(), cause).call();
        constructions.rubble(s);
        TagResolver[] args = {arg("name", type.name()), arg("town", town == null ? "?" : town.name()), coords(s)};
        if (type.isWonder()) broadcast("structure.wonder.destroyed", args);
        else if (town != null) tellTown(town, "structure.destroyed", args);
    }

    /** Destroys every wonder of a captured / sold town (spec 03 §4.5). */
    public void destroyWonders(Town town, StructureDestroyedEvent.Cause cause) {
        for (Structure s : List.copyOf(index.town(town.id()))) {
            if (s.typeDef().isWonder()) destroy(s, cause);
        }
    }

    /** Removes every structure of a town (disband). Terrain is left as it is. */
    public void removeAll(Town town, StructureDestroyedEvent.Cause cause, boolean restoreTerrain) {
        for (Structure s : List.copyOf(index.town(town.id()))) remove(s, cause, restoreTerrain);
    }

    /** Starts the repair of a destroyed structure (spec 02 §1.6): 50% of the price, hammers / 3. */
    public void startRepair(Structure s, Player player) throws CivException {
        Tasks.checkMain();
        CivException.check(s.isDestroyed(), "structure.error.not-destroyed");
        CivException.check(!s.typeDef().isWonder(), "structure.error.wonder-no-repair");
        CivException.check(!s.locked(), "structure.error.locked");
        CivException.check(!s.repairing(), "structure.error.repairing");
        CivException.check(!warActive(), "structure.error.war");
        Town town = town(s);
        CivException.check(town != null, "error.not-in-town");
        long cost = Money.multiply(s.typeDef().cost(), settings.repairCostShare());
        if (town.treasury() < cost) throw new CivException("structure.error.money", money("cost", cost));
        town.addTreasury(-cost);
        civ.state().save(town);
        s.repairing(true);
        s.repairDone(0);
        s.repairRequired(Math.max(1, s.typeDef().hammers() / settings.repairHammersDivisor()));
        save(s);
        tellTown(town, "structure.repair.started", arg("name", s.typeDef().name()), money("cost", cost));
    }

    void finishRepair(Structure s) {
        Town town = town(s);
        s.repairing(false);
        s.state(Structure.State.COMPLETE);
        s.destroyedAt(null);
        int hp = computeMaxHp(s, town);
        s.maxHp(hp);
        s.hp(hp);
        save(s);
        constructions.forget(s);
        constructions.refresh(s);
        safe(s, "onRepaired", () -> behavior(s.type()).onRepaired(s));
        civ.stats().invalidate();
        new StructureRepairedEvent(s.id(), s.type(), s.townId()).call();
        if (town != null) tellTown(town, "structure.repair.done", arg("name", s.typeDef().name()));
    }

    /** Restores missing blocks from the template (spec 02 §1.7 refreshnearest); honours the cooldown unless forced. */
    public void refresh(Structure s, boolean force) throws CivException {
        Tasks.checkMain();
        CivException.check(s.state() == Structure.State.COMPLETE, "structure.error.not-complete");
        CivException.check(s.template() != null, "structure.error.no-template");
        if (!force && s.lastRefresh() != null) {
            Instant next = s.lastRefresh().plusSeconds(settings.refreshCooldownMinutes() * 60L);
            if (next.isAfter(Instant.now())) {
                throw new CivException("structure.error.refresh-cooldown",
                        arg("minutes", Math.max(1, (next.getEpochSecond() - Instant.now().getEpochSecond() + 59) / 60)));
            }
        }
        s.lastRefresh(Instant.now());
        save(s);
        constructions.refresh(s);
    }

    /** Changes the level (upgrades, production levels); fires {@link StructureLevelChangedEvent}. */
    public void setLevel(Structure s, int level) {
        Tasks.checkMain();
        int max = s.typeDef().topLevel();
        int value = Math.max(0, Math.min(max, level));
        int old = s.level();
        if (old == value) return;
        s.level(value);
        save(s);
        safe(s, "onLevelChanged", () -> behavior(s.type()).onLevelChanged(s, old, value));
        civ.stats().invalidate();
        new StructureLevelChangedEvent(s.id(), s.type(), s.townId(), old, value).call();
    }

    /** Enables or disables a structure's effects and behaviours (e.g. bank without the required level). */
    public void setEnabled(Structure s, boolean enabled) {
        if (s.enabled() == enabled) return;
        s.enabled(enabled);
        save(s);
        civ.stats().invalidate();
    }

    /** Spy sabotage: no repair or demolition until the given time (spec 02 §1.6). */
    public void lock(Structure s, Instant until) {
        s.lockedUntil(until);
        save(s);
    }

    /** Demolition by a player (permission checked by the caller), no refund (spec 02 §1.7). */
    public void demolish(Structure s) throws CivException {
        Tasks.checkMain();
        CivException.check(s.typeDef().demolishable(), "structure.error.no-demolish");
        CivException.check(!s.locked(), "structure.error.locked");
        Town town = town(s);
        CivException.check(town == null || !town.isCaptured(), "structure.error.captured");
        if (s.isBuilding()) {
            cancel(s, ConstructionCancelledEvent.Reason.CANCELLED, true, 0);
            return;
        }
        remove(s, StructureDestroyedEvent.Cause.DEMOLISHED, true);
        if (town != null) tellTown(town, "structure.demolished", arg("name", s.typeDef().name()));
    }

    /**
     * Registers a template-less structure (wall, road) placed by a {@link CustomPlacement}: already paid, validated
     * and built. Its blocks become protected structure blocks.
     */
    public Structure placeCustom(Town town, String typeId, List<BlockPos> blocks, long paid) throws CivException {
        Tasks.checkMain();
        StructureType type = types.get(typeId);
        CivException.check(type != null && !blocks.isEmpty(), "structure.error.unknown-type", arg("name", typeId));
        BlockPos first = blocks.getFirst();
        Structure s = new Structure(newId(), type, town.id(), first, StructureRotation.NONE, defaultTheme(town), type.id(),
                false, 1, 1, 1);
        s.customBlocks(blocks);
        s.components(List.of());
        s.paidCost(paid);
        s.state(Structure.State.COMPLETE);
        s.completedAt(Instant.now());
        s.markersBuilt(true);
        int hp = computeMaxHp(s, town);
        s.maxHp(hp);
        s.hp(hp);
        index.add(s);
        save(s);
        safe(s, "onPlaced", () -> behavior(type.id()).onPlaced(s));
        new StructurePlacedEvent(s.id(), s.type(), town.id()).call();
        safe(s, "onComplete", () -> behavior(type.id()).onComplete(s));
        civ.stats().invalidate();
        new StructureCompletedEvent(s.id(), s.type(), town.id()).call();
        return s;
    }

    /** Admin: finishes construction (and pastes everything that is loaded) immediately. */
    public void completeNow(Structure s) {
        Tasks.checkMain();
        if (s.removed()) return;
        if (s.isBuilding()) {
            s.hammersDone(s.hammersRequired());
            complete(s);
        } else if (s.repairing()) {
            finishRepair(s);
        }
        constructions.pasteNow(s);
    }

    /** Admin: full rebuild – HP, control blocks and every block of the template. */
    public void rebuild(Structure s) {
        Tasks.checkMain();
        if (s.removed()) return;
        if (s.isDestroyed()) {
            finishRepair(s);
        } else if (s.complete()) {
            s.hp(s.maxHp());
            save(s);
            constructions.refresh(s);
        }
        controlPoints.repairAll(s);
    }

    // =================================================================================================================
    // economy
    // =================================================================================================================

    /** Daily upkeep of all structures of the town, hundredths, before government multipliers (spec 02 §1.5). */
    public long structuresUpkeep(Town town) {
        long total = 0;
        for (Structure s : index.town(town.id())) {
            if (s.removed() || (s.isBuilding() && !settings.upkeepUnderConstruction())) continue;
            total = Math.addExact(total, upkeep(s, town));
        }
        return total;
    }

    /** Daily upkeep of one structure including per-tag modifiers (Aztec towers, Turkish naval...). */
    public long upkeep(Structure s, Town town) {
        StructureType type = s.typeDef();
        double percent = civ.stats().town(town).percent("upkeep." + type.category().key());
        for (String tag : type.tags()) percent += civ.stats().town(town).percent("upkeep." + tag);
        long base = Money.multiply(type.upkeep(), Math.max(0, 1 + percent));
        long extra = 0;
        try {
            extra = Math.max(0, behavior(s.type()).extraUpkeep(s));
        } catch (RuntimeException e) {
            civ.logger().log(Level.SEVERE, "extraUpkeep failed for " + s.type(), e);
        }
        return base + extra;
    }

    /** Score of the town's complete, working structures (spec 02 §2 column «Очки»). */
    public long score(Town town) {
        long total = 0;
        for (Structure s : index.town(town.id())) {
            if (s.state() == Structure.State.COMPLETE) total += s.typeDef().score();
        }
        return total;
    }

    public long score(Civilization c) {
        long total = 0;
        for (Town t : civ.state().towns(c)) total += score(t);
        return total;
    }

    /** Whether the building requirements of a national wonder hold (its effects stop otherwise). */
    boolean requirementsHold(Structure s, Town town) {
        Civilization c = civ.state().civOf(town);
        for (Requirement r : s.typeDef().requires()) {
            List<String> ids = types.satisfying(r.type());
            if (r.scope() == Requirement.Scope.TOWN) {
                if (!hasWorking(town, ids, r.level())) return false;
                continue;
            }
            if (c == null) return false;
            for (Town t : civ.state().towns(c)) {
                if (t.isCaptured()) continue;
                if (r.scope() == Requirement.Scope.NATIVE_TOWNS && t.status() != com.civcraft.model.TownStatus.NATIVE) continue;
                if (!hasWorking(t, ids, r.level())) return false;
            }
        }
        return true;
    }

    private void hourly() {
        for (Structure s : List.copyOf(index.all())) {
            if (!s.isActive() || !behaviors.containsKey(s.type())) continue;
            safe(s, "onHourly", () -> behavior(s.type()).onHourly(s));
        }
    }

    private void daily() {
        for (Structure s : List.copyOf(index.all())) {
            if (!s.isActive() || !behaviors.containsKey(s.type())) continue;
            safe(s, "onDaily", () -> behavior(s.type()).onDaily(s));
        }
    }

    // =================================================================================================================
    // messages
    // =================================================================================================================

    public TagResolver coords(Structure s) {
        BlockPos c = s.center();
        return arg("coords", c.x() + ", " + s.origin().y() + ", " + c.z());
    }

    public void tellTown(Town town, String key, TagResolver... args) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Resident r = civ.state().resident(p);
            if (r != null && town.id().equals(r.townId())) civ.messages().send(p, key, args);
        }
    }

    public void broadcast(String key, TagResolver... args) {
        for (Player p : Bukkit.getOnlinePlayers()) civ.messages().send(p, key, args);
    }

    boolean isMember(Player player, Town town) {
        Resident r = civ.state().resident(player);
        return r != null && town.id().equals(r.townId());
    }

    boolean isCivMember(Player player, Town town) {
        Resident r = civ.state().resident(player);
        Town own = civ.state().townOf(r);
        return own != null && town.civId() != null && town.civId().equals(own.civId()) && !own.isCaptured();
    }
}
