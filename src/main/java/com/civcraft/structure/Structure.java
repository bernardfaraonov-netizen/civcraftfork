package com.civcraft.structure;

import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Cuboid;
import com.civcraft.storage.Stored;
import com.civcraft.structure.component.StructureComponent;
import com.civcraft.structure.construction.UndoBuffer;
import com.civcraft.structure.type.StructureType;
import com.civcraft.template.Template;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.structure.StructureRotation;

/**
 * A placed structure: persisted document (collection {@code structures}) plus transient runtime data (resolved
 * template, solid-cell mask, components, undo buffer). Mutated only on the main thread through
 * {@link StructureModule}; other modules read it through {@link StructureApi.Placed}.
 */
public final class Structure implements Stored, StructureApi.Placed {

    public enum State {
        /** Under construction; progress in hammers. */
        BUILDING,
        /** Built and working (unless disabled by a behaviour). */
        COMPLETE,
        /** Destroyed in war: ruins that give nothing until repaired (wonders cannot be repaired). */
        DESTROYED
    }

    private String id;
    private String type;
    private String townId;
    private BlockPos origin;
    private StructureRotation rotation = StructureRotation.NONE;
    private String theme;
    private String templateId;
    private boolean procedural;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private State state = State.BUILDING;
    private double hammersDone;
    private double hammersRequired;
    /** Next template cell to paste (cells are ordered bottom-up). */
    private int cursor;
    private boolean markersBuilt;
    private int level = 1;
    private int hp;
    private int maxHp;
    private long paidCost;
    private Instant created;
    private Instant completed;
    private Instant destroyedAt;
    private Instant lockedUntil;
    private Instant lastRefresh;
    /** Replaces the town's current main building when complete (town hall relocation, capitol). */
    private boolean replacesMain;
    private boolean repairing;
    private double repairDone;
    private double repairRequired;
    private int announcedPercent;
    private boolean enabled = true;
    private List<ChunkKey> autoClaims = new ArrayList<>();
    private List<ChunkKey> lockedClaims = new ArrayList<>();
    private List<ControlPoint> controlPoints = new ArrayList<>();
    /** Explicit blocks of template-less structures (walls, roads); null for template structures. */
    private List<BlockPos> customBlocks;
    /** Free-form persistent data for behaviours (levels progress, fees, stored items...). */
    private JsonObject data = new JsonObject();

    private transient StructureType typeRef;
    private transient Template template;
    private transient BitSet solid;
    private transient Cuboid cuboid;
    private transient Set<BlockPos> customSet;
    private transient List<StructureComponent> components = List.of();
    private transient UndoBuffer undo;
    private transient boolean removed;
    private transient boolean dirty;

    private Structure() {
    }

    Structure(String id, StructureType type, String townId, BlockPos origin, StructureRotation rotation, String theme,
              String templateId, boolean procedural, int sizeX, int sizeY, int sizeZ) {
        this.id = id;
        this.type = type.id();
        this.typeRef = type;
        this.townId = townId;
        this.origin = origin;
        this.rotation = rotation;
        this.theme = theme;
        this.templateId = templateId;
        this.procedural = procedural;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.created = Instant.now();
        this.level = Math.max(0, type.startLevel());
    }

    @Override
    public String storageId() {
        return id;
    }

    // --- StructureApi.Placed ------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String townId() {
        return townId;
    }

    @Override
    public BlockPos origin() {
        return origin;
    }

    @Override
    public int sizeX() {
        return sizeX;
    }

    @Override
    public int sizeY() {
        return sizeY;
    }

    @Override
    public int sizeZ() {
        return sizeZ;
    }

    @Override
    public boolean complete() {
        return state != State.BUILDING;
    }

    @Override
    public double progress() {
        if (state != State.BUILDING) return 1;
        return hammersRequired <= 0 ? 1 : Math.max(0, Math.min(1, hammersDone / hammersRequired));
    }

    @Override
    public int level() {
        return level;
    }

    @Override
    public boolean contains(BlockPos pos) {
        if (customBlocks != null) return customSet().contains(pos);
        return cuboid().contains(pos);
    }

    @Override
    public BlockPos center() {
        if (customBlocks != null && !customBlocks.isEmpty()) return customBlocks.get(customBlocks.size() / 2);
        return new BlockPos(origin.world(), origin.x() + sizeX / 2, origin.y() + sizeY / 2, origin.z() + sizeZ / 2);
    }

    // --- state ------------------------------------------------------------------------------------------------------

    public StructureType typeDef() {
        return typeRef;
    }

    void typeDef(StructureType type) {
        this.typeRef = type;
    }

    public State state() {
        return state;
    }

    void state(State state) {
        this.state = state;
    }

    public boolean isBuilding() {
        return state == State.BUILDING;
    }

    public boolean isDestroyed() {
        return state == State.DESTROYED;
    }

    /** Complete, not destroyed and not disabled: effects and behaviours run. */
    public boolean isActive() {
        return state == State.COMPLETE && enabled && !removed;
    }

    public boolean removed() {
        return removed;
    }

    void markRemoved() {
        this.removed = true;
    }

    public boolean enabled() {
        return enabled;
    }

    void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public StructureRotation rotation() {
        return rotation;
    }

    public String theme() {
        return theme;
    }

    public String templateId() {
        return templateId;
    }

    public boolean procedural() {
        return procedural;
    }

    public double hammersDone() {
        return hammersDone;
    }

    void hammersDone(double value) {
        this.hammersDone = Math.max(0, value);
    }

    public double hammersRequired() {
        return hammersRequired;
    }

    void hammersRequired(double value) {
        this.hammersRequired = Math.max(0, value);
    }

    public int cursor() {
        return cursor;
    }

    void cursor(int cursor) {
        this.cursor = cursor;
    }

    public boolean markersBuilt() {
        return markersBuilt;
    }

    void markersBuilt(boolean built) {
        this.markersBuilt = built;
    }

    void level(int level) {
        this.level = level;
    }

    public int hp() {
        return hp;
    }

    void hp(int hp) {
        this.hp = Math.max(0, Math.min(maxHp, hp));
    }

    public int maxHp() {
        return maxHp;
    }

    void maxHp(int maxHp) {
        this.maxHp = Math.max(0, maxHp);
        if (hp > this.maxHp) hp = this.maxHp;
    }

    public long paidCost() {
        return paidCost;
    }

    void paidCost(long paid) {
        this.paidCost = paid;
    }

    public Instant created() {
        return created;
    }

    public Instant completedAt() {
        return completed;
    }

    void completedAt(Instant at) {
        this.completed = at;
    }

    public Instant destroyedAt() {
        return destroyedAt;
    }

    void destroyedAt(Instant at) {
        this.destroyedAt = at;
    }

    /** Sabotage lock: no repair or demolition until this moment (spec 02 §1.6). */
    public Instant lockedUntil() {
        return lockedUntil;
    }

    void lockedUntil(Instant until) {
        this.lockedUntil = until;
    }

    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public Instant lastRefresh() {
        return lastRefresh;
    }

    void lastRefresh(Instant at) {
        this.lastRefresh = at;
    }

    public boolean replacesMain() {
        return replacesMain;
    }

    void replacesMain(boolean value) {
        this.replacesMain = value;
    }

    public boolean repairing() {
        return repairing;
    }

    void repairing(boolean value) {
        this.repairing = value;
    }

    public double repairDone() {
        return repairDone;
    }

    void repairDone(double value) {
        this.repairDone = value;
    }

    public double repairRequired() {
        return repairRequired;
    }

    void repairRequired(double value) {
        this.repairRequired = value;
    }

    int announcedPercent() {
        return announcedPercent;
    }

    void announcedPercent(int percent) {
        this.announcedPercent = percent;
    }

    public List<ChunkKey> autoClaims() {
        return autoClaims;
    }

    public List<ChunkKey> lockedClaims() {
        return lockedClaims;
    }

    public List<ControlPoint> controlPoints() {
        return controlPoints;
    }

    public List<BlockPos> customBlocks() {
        return customBlocks == null ? null : Collections.unmodifiableList(customBlocks);
    }

    void customBlocks(List<BlockPos> blocks) {
        this.customBlocks = new ArrayList<>(blocks);
        this.customSet = null;
    }

    /** Behaviour data; call {@link StructureModule#save(Structure)} after changing it. */
    public JsonObject data() {
        if (data == null) data = new JsonObject();
        return data;
    }

    // --- runtime ----------------------------------------------------------------------------------------------------

    public Template template() {
        return template;
    }

    void template(Template template, BitSet solid) {
        this.template = template;
        this.solid = solid;
    }

    public Cuboid cuboid() {
        if (cuboid == null) {
            if (customBlocks != null && !customBlocks.isEmpty()) {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (BlockPos p : customBlocks) {
                    minX = Math.min(minX, p.x());
                    minY = Math.min(minY, p.y());
                    minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x());
                    maxY = Math.max(maxY, p.y());
                    maxZ = Math.max(maxZ, p.z());
                }
                cuboid = new Cuboid(origin.world(), minX, minY, minZ, maxX, maxY, maxZ);
            } else {
                cuboid = Cuboid.of(origin, Math.max(1, sizeX), Math.max(1, sizeY), Math.max(1, sizeZ));
            }
        }
        return cuboid;
    }

    private Set<BlockPos> customSet() {
        if (customSet == null) customSet = new HashSet<>(customBlocks);
        return customSet;
    }

    /** Chunks touched by the structure (template volume or custom blocks). */
    public Set<ChunkKey> chunks() {
        if (customBlocks != null) {
            Set<ChunkKey> result = new HashSet<>();
            for (BlockPos p : customBlocks) result.add(p.chunk());
            return result;
        }
        return cuboid().chunks();
    }

    public int cellCount() {
        return sizeX * sizeY * sizeZ;
    }

    /** Template cell index of a world position inside the cuboid, or -1. */
    public int cellOf(BlockPos pos) {
        if (customBlocks != null || !cuboid().contains(pos)) return -1;
        int x = pos.x() - origin.x();
        int y = pos.y() - origin.y();
        int z = pos.z() - origin.z();
        return x + z * sizeX + y * sizeX * sizeZ;
    }

    /**
     * Whether the block is part of the building (a non-air template cell or marker that has been placed already).
     * Air cells inside the volume are not structure blocks.
     */
    public boolean isStructureBlock(BlockPos pos) {
        if (customBlocks != null) return customSet().contains(pos);
        int cell = cellOf(pos);
        if (cell < 0) return false;
        if (state == State.BUILDING && cell >= cursor) return false;
        return solid == null || solid.get(cell);
    }

    public List<StructureComponent> components() {
        return components;
    }

    public List<StructureComponent> components(String markerType) {
        List<StructureComponent> result = new ArrayList<>();
        for (StructureComponent c : components) if (c.type().equals(markerType)) result.add(c);
        return result;
    }

    void components(List<StructureComponent> list) {
        this.components = List.copyOf(list);
    }

    UndoBuffer undo() {
        return undo;
    }

    void undo(UndoBuffer undo) {
        this.undo = undo;
    }

    boolean dirty() {
        return dirty;
    }

    void dirty(boolean dirty) {
        this.dirty = dirty;
    }
}
