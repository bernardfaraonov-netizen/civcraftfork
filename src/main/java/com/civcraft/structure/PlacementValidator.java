package com.civcraft.structure;

import static com.civcraft.core.text.Messages.arg;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.text.Format;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.civcraft.core.util.Cuboid;
import com.civcraft.core.util.Money;
import com.civcraft.model.Civilization;
import com.civcraft.model.Claim;
import com.civcraft.model.Town;
import com.civcraft.model.TownStatus;
import com.civcraft.structure.type.Category;
import com.civcraft.structure.type.ClaimRule;
import com.civcraft.structure.type.Requirement;
import com.civcraft.structure.type.StructureType;
import com.civcraft.template.TemplateService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/**
 * Every placement rule in one place (spec 02 §1.3). Checks throw {@link CivException}; there are no silent returns
 * that skip later checks (audit B #11), and nothing is changed until every check passed (audit B #12, #15, #20).
 */
final class PlacementValidator {

    /** A candidate site: world volume of the rotated template. */
    record Site(World world, BlockPos origin, int sizeX, int sizeY, int sizeZ) {

        Cuboid cuboid() {
            return Cuboid.of(origin, sizeX, sizeY, sizeZ);
        }

        BlockPos center() {
            return origin.offset(sizeX / 2, 0, sizeZ / 2);
        }
    }

    private final StructureModule module;
    private final CivCraft civ;

    PlacementValidator(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    // --- type availability ----------------------------------------------------------------------------------------

    /** Checks whether the town may build the type at all (tech, limits, slots, wonder rules...). */
    void checkAvailable(Town town, StructureType type) throws CivException {
        StructureSettings cfg = module.settings();
        Civilization c = civ.state().civOf(town);
        CivException.check(!type.warOnly(), "structure.error.war-only");
        if (type.nation() != null && (c == null || !module.types().nationMatches(type.nation(), c.nation()))) {
            throw new CivException("structure.error.nation", arg("nation", civ.messages().plain("structure.nation." + type.nation())));
        }
        if (c != null && c.nation() != null) {
            StructureType replacement = module.types().replacementFor(type.id(), c.nation());
            if (replacement != null) throw new CivException("structure.error.replaced", arg("name", replacement.name()));
        }
        if (type.isWonder() && (c == null || c.isProvince())) throw new CivException("structure.error.province-wonder");
        for (String tech : type.techs()) {
            if (!module.integrations().hasTech(town, tech)) {
                throw new CivException("structure.error.tech", arg("tech", module.integrations().techName(tech)));
            }
        }
        if (type.capitalOnly() && (c == null || !town.id().equals(c.capitalId()))) throw new CivException("structure.error.capital-only");
        if (type.cultureLevel() > 0 && module.integrations().cultureLevel(town) < type.cultureLevel()) {
            throw new CivException("structure.error.culture-level", arg("level", type.cultureLevel()));
        }
        Structure main = module.mainBuilding(town);
        if (!type.isMain()) {
            CivException.check(main != null && !main.isBuilding(), "structure.error.no-main");
        } else {
            checkMain(town, type, main);
        }
        for (Requirement r : type.requires()) checkRequirement(town, c, r);
        checkLimits(town, c, type);
        if (type.slot()) {
            int used = module.slotsUsed(town);
            int max = module.integrations().slots(town);
            if (used >= max) throw new CivException("structure.error.slots", arg("used", used), arg("max", max));
        }
        if (type.isWorldWonder()) checkWorldWonder(town, c, type, cfg);
        if (type.category() == Category.NATIONAL_WONDER && c != null) {
            for (Town t : civ.state().towns(c)) {
                for (Structure s : module.index().town(t.id())) {
                    if (s.type().equals(type.id()) && !s.isDestroyed()) {
                        throw new CivException("structure.error.national-exists", arg("town", t.name()));
                    }
                }
            }
        }
        if (!type.category().isCustom()) {
            Structure busy = type.isWonder() ? module.buildingWonder(town) : module.buildingNormal(town);
            if (busy != null) {
                throw new CivException(type.isWonder() ? "structure.error.busy-wonder" : "structure.error.busy",
                        arg("name", busy.typeDef().name()));
            }
        }
        CivException.check(!town.inDebt(), "structure.error.debt");
        CivException.check(!town.convertingHammers(), "structure.error.chammers");
        CivException.check(!module.warActive(), "structure.error.war");
        for (BiFunction<Town, String, Optional<String>> gate : module.availabilityGates()) {
            Optional<String> reason = gate.apply(town, type.id());
            if (reason != null && reason.isPresent()) throw new CivException(reason.get());
        }
    }

    private void checkMain(Town town, StructureType type, Structure main) throws CivException {
        if (main != null && main.isBuilding()) throw new CivException("structure.error.main-in-progress");
        for (Structure s : module.index().town(town.id())) {
            if (s.typeDef().isMain() && s.isBuilding()) throw new CivException("structure.error.main-in-progress");
        }
        if (type.id().equals(StructureModule.TOWN_HALL) && main != null && main.type().equals(StructureModule.CAPITOL)) {
            throw new CivException("structure.error.has-capitol");
        }
        if (main != null) {
            // Relocation (or capitol replacing the town hall): not in captured towns (spec 02 §1.2).
            CivException.check(town.status() != TownStatus.CAPTURED, "structure.error.captured-relocate");
        }
    }

    private void checkRequirement(Town town, Civilization c, Requirement r) throws CivException {
        StructureType req = module.types().get(r.type());
        String name = req == null ? r.type() : req.name();
        List<String> ids = module.types().satisfying(r.type());
        switch (r.scope()) {
            case TOWN -> {
                if (!module.hasWorking(town, ids, r.level())) {
                    throw new CivException(r.level() > 0 ? "structure.error.requires-level" : "structure.error.requires",
                            arg("name", name), arg("level", r.level()));
                }
            }
            case ALL_TOWNS, NATIVE_TOWNS -> {
                if (c == null) throw new CivException("structure.error.requires-all", arg("name", name));
                for (Town t : civ.state().towns(c)) {
                    if (t.isCaptured()) continue;
                    if (r.scope() == Requirement.Scope.NATIVE_TOWNS && t.status() != TownStatus.NATIVE) continue;
                    if (!module.hasWorking(t, ids, r.level())) {
                        throw new CivException("structure.error.requires-all", arg("name", name), arg("town", t.name()));
                    }
                }
            }
        }
    }

    private void checkLimits(Town town, Civilization c, StructureType type) throws CivException {
        if (type.isMain()) return;
        int bonus = c == null ? 0 : (int) Math.floor(civ.stats().civ(c).get("limit." + type.id()));
        if (type.limit() > 0) {
            int limit = type.limit() + bonus;
            int count = module.countInTown(town, type);
            if (count >= limit) throw new CivException("structure.error.limit", arg("limit", limit));
        }
        if (type.civLimit() > 0 && c != null) {
            int limit = type.civLimit() + bonus;
            int count = 0;
            for (Town t : civ.state().towns(c)) count += module.countInTown(t, type);
            if (count >= limit) throw new CivException("structure.error.civ-limit", arg("limit", limit));
        }
    }

    private void checkWorldWonder(Town town, Civilization c, StructureType type, StructureSettings cfg) throws CivException {
        if (town.founded() != null && Duration.between(town.founded(), Instant.now()).toDays() < cfg.wonderMinAgeDays()) {
            throw new CivException("structure.error.wonder-age", arg("days", cfg.wonderMinAgeDays()));
        }
        CivException.check(town.status() != TownStatus.CAPTURED, "structure.error.wonder-captured");
        for (Structure s : module.index().all()) {
            if (!s.type().equals(type.id())) continue;
            Town owner = civ.state().town(s.townId());
            if (s.state() == Structure.State.COMPLETE) {
                throw new CivException("structure.error.wonder-exists", arg("town", owner == null ? "?" : owner.name()));
            }
            if (s.isBuilding() && owner != null && c != null && c.id().equals(owner.civId())) {
                throw new CivException("structure.error.wonder-building", arg("town", owner.name()));
            }
        }
        int wonders = 0;
        for (Structure s : module.index().town(town.id())) {
            if (s.typeDef().usesWonderSlot() && !s.isDestroyed()) wonders++;
        }
        if (wonders >= cfg.wondersPerTown()) throw new CivException("structure.error.wonder-slots", arg("max", cfg.wondersPerTown()));
    }

    // --- site -------------------------------------------------------------------------------------------------------

    /** Checks the terrain and territory of a site. {@code ignore} is excluded from overlap checks (repairs). */
    void checkSite(Town town, StructureType type, Site site, Structure ignore) throws CivException {
        StructureSettings cfg = module.settings();
        World world = site.world();
        CivException.check(world != null && civ.settings().isGameWorld(world), "structure.error.world");
        if (type.isWonder() && !cfg.wonderWorlds().isEmpty() && !cfg.wonderWorlds().contains(world.getName())) {
            throw new CivException("structure.error.world");
        }
        int y = site.origin().y();
        int minY = Math.max(world.getMinHeight() + 1, cfg.minY());
        if (type.minY() != null) minY = Math.max(minY, type.minY());
        if (y < minY) throw new CivException("structure.error.too-low", arg("y", minY));
        int maxY = world.getMaxHeight() - site.sizeY();
        if (type.maxY() != null) maxY = Math.min(maxY, type.maxY());
        if (y > maxY) throw new CivException("structure.error.too-high", arg("y", maxY));

        WorldBorder border = world.getWorldBorder();
        Cuboid box = site.cuboid();
        // Terrain checks read blocks; never load (or generate) chunks for them.
        for (ChunkKey chunk : box.chunks()) {
            if (!world.isChunkLoaded(chunk.x(), chunk.z())) throw new CivException("structure.error.not-loaded");
        }
        for (int[] c : new int[][]{{box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}}) {
            if (!border.isInside(new Location(world, c[0] + 0.5, y, c[1] + 0.5))) throw new CivException("structure.error.border");
        }

        if (type.water().isWater()) {
            checkWater(type, site);
        } else if (!type.floating()) {
            double support = TemplateService.groundSupport(world, site.origin(), site.sizeX(), site.sizeZ());
            if (support < cfg.groundRatio()) {
                throw new CivException("structure.error.ground", arg("percent", Format.number(Math.floor(support * 100))),
                        arg("required", Format.number(cfg.groundRatio() * 100)));
            }
        }

        for (ChunkKey chunk : box.chunks()) {
            if (!civ.culture().inCulture(town, chunk)) throw new CivException("structure.error.culture");
            Claim claim = civ.state().claim(chunk);
            if (claim != null && !claim.townId().equals(town.id())) {
                Town other = civ.state().town(claim.townId());
                throw new CivException("structure.error.foreign-claim", arg("town", other == null ? "?" : other.name()));
            }
            if (type.claim() == ClaimRule.CLAIMED && claim == null) throw new CivException("structure.error.not-claimed");
        }

        for (ChunkKey chunk : box.chunks()) {
            for (Structure s : module.index().chunk(chunk)) {
                if (s == ignore || s.removed()) continue;
                if (overlaps(s, box)) throw new CivException("structure.error.overlap", arg("name", s.typeDef().name()));
            }
        }

        if (type.isMain()) checkMainDistance(town, site, cfg);
        if (type.spacingGroup() != null) checkSpacing(town, type, site);

        for (FootprintGate gate : module.footprintGates()) {
            Optional<String> reason = gate.check(town, type, box);
            if (reason != null && reason.isPresent()) throw new CivException(reason.get());
        }
    }

    private static boolean overlaps(Structure s, Cuboid box) {
        if (s.customBlocks() != null) {
            for (BlockPos p : s.customBlocks()) if (box.contains(p)) return true;
            return false;
        }
        return s.cuboid().intersects(box);
    }

    private void checkWater(StructureType type, Site site) throws CivException {
        World world = site.world();
        int sea = world.getSeaLevel() - 1;
        int water = 0;
        int total = 0;
        for (int x = 0; x < site.sizeX(); x++) {
            for (int z = 0; z < site.sizeZ(); z++) {
                total++;
                Block b = world.getBlockAt(site.origin().x() + x, sea, site.origin().z() + z);
                BlockData data = b.getBlockData();
                if (b.getType() == Material.WATER || (data instanceof Waterlogged w && w.isWaterlogged())
                        || b.getType() == Material.KELP_PLANT || b.getType() == Material.SEAGRASS) {
                    water++;
                }
            }
        }
        double ratio = total == 0 ? 0 : water / (double) total;
        if (ratio < module.settings().waterMinRatio()) {
            throw new CivException("structure.error.water-ratio", arg("percent", Format.number(module.settings().waterMinRatio() * 100)));
        }
        for (ChunkKey chunk : site.cuboid().chunks()) {
            int cx = (chunk.x() << 4) + 8;
            int cz = (chunk.z() << 4) + 8;
            if (!type.water().acceptsBiome(world.getBiome(cx, sea, cz))) throw new CivException("structure.error.water-biome");
        }
    }

    private void checkMainDistance(Town town, Site site, StructureSettings cfg) throws CivException {
        double min = cfg.mainBuildingDistance();
        if (min <= 0) return;
        BlockPos center = site.center();
        for (Town other : civ.state().towns()) {
            if (other.id().equals(town.id())) continue;
            BlockPos pos = null;
            Structure main = module.mainBuilding(other);
            if (main != null) pos = main.center();
            else if (other.center() != null) pos = other.center();
            if (pos == null || !pos.world().equals(center.world())) continue;
            double dx = pos.x() - center.x();
            double dz = pos.z() - center.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < min) {
                throw new CivException("structure.error.main-distance", arg("distance", Format.number(min)), arg("town", other.name()));
            }
        }
    }

    private void checkSpacing(Town town, StructureType type, Site site) throws CivException {
        Civilization c = civ.state().civOf(town);
        double spacing = type.spacing() + (c == null ? 0 : civ.stats().civ(c).get("spacing." + type.spacingGroup()));
        if (spacing <= 0) return;
        BlockPos center = site.center();
        for (Structure s : module.index().all()) {
            StructureType other = s.typeDef();
            if (other == null || !type.spacingGroup().equals(other.spacingGroup())) continue;
            Town owner = civ.state().town(s.townId());
            boolean sameCiv = owner != null && (owner.id().equals(town.id()) || (c != null && c.id().equals(owner.civId())));
            if (!sameCiv) continue;
            BlockPos p = s.center();
            if (!p.world().equals(center.world())) continue;
            double dx = p.x() - center.x();
            double dz = p.z() - center.z();
            if (dx * dx + dz * dz < spacing * spacing) {
                throw new CivException("structure.error.spacing", arg("name", other.name()), arg("distance", Format.number(spacing)));
            }
        }
    }

    // --- price --------------------------------------------------------------------------------------------------------

    /** Price in hundredths after build cost modifiers (spec 02 §1.4 table). */
    long cost(Town town, StructureType type, boolean relocation) {
        long base = relocation ? type.relocateCost() : type.cost();
        if (base <= 0) return 0;
        double percent = civ.stats().town(town).percent("build_cost");
        for (String tag : type.tags()) percent += civ.stats().town(town).percent("build_cost." + tag);
        percent += civ.stats().town(town).percent("build_cost." + type.category().key());
        return Math.max(0, Money.multiply(base, Math.max(0, 1 + percent)));
    }

    /** Hammers after architects' experience (wonders) and hammer modifiers, in that order (spec 02 §1.11). */
    double hammers(Town town, StructureType type) {
        double base = type.hammers();
        if (type.isWonder()) {
            Civilization c = civ.state().civOf(town);
            base *= 1 - (c == null ? 0 : module.architectsBonus(c.id(), type.id()));
        }
        double percent = civ.stats().town(town).percent("build_hammers");
        for (String tag : type.tags()) percent += civ.stats().town(town).percent("build_hammers." + tag);
        percent += civ.stats().town(town).percent("build_hammers." + type.category().key());
        return Math.max(1, base * Math.max(0.05, 1 + percent));
    }
}
