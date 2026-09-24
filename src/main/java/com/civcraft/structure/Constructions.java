package com.civcraft.structure;

import static com.civcraft.core.text.Messages.arg;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Format;
import com.civcraft.core.ui.Holograms;
import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.Durations;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.structure.construction.BlockWork;
import com.civcraft.structure.construction.BuildMath;
import com.civcraft.structure.construction.SweepJob;
import com.civcraft.structure.type.StructureType;
import com.civcraft.template.Template;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Progress of constructions and repairs (every second, main thread), progressive pasting of template blocks within a
 * per-tick budget, progress boss bars and holograms, regeneration, rubble and refresh sweeps. Nothing here sleeps or
 * runs asynchronously (audit B #4, #7, #8, #72).
 */
final class Constructions {

    private final StructureModule module;
    private final CivCraft civ;
    /** Structures with blocks still to paste (under construction or completed with unloaded parts). */
    private final Set<Structure> pasting = new LinkedHashSet<>();
    private final Set<Structure> damaged = new LinkedHashSet<>();
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();
    private int seconds;

    Constructions(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
        for (Structure s : module.index().all()) {
            if (needsPaste(s)) pasting.add(s);
            if (s.complete() && !s.isDestroyed() && s.hp() < s.maxHp()) damaged.add(s);
        }
    }

    private static boolean needsPaste(Structure s) {
        return s.customBlocks() == null && s.template() != null && (s.isBuilding() || !s.markersBuilt());
    }

    // --- per second -------------------------------------------------------------------------------------------------

    void tickSecond() {
        seconds++;
        StructureSettings cfg = module.settings();
        Map<String, List<Structure>> building = new HashMap<>();
        Map<String, List<Structure>> repairing = new HashMap<>();
        for (Structure s : module.index().all()) {
            if (s.removed()) continue;
            if (s.isBuilding() && s.customBlocks() == null) building.computeIfAbsent(s.townId(), k -> new ArrayList<>()).add(s);
            else if (s.repairing()) repairing.computeIfAbsent(s.townId(), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Structure>> e : building.entrySet()) {
            Town town = civ.state().town(e.getKey());
            if (town == null) continue;
            if (town.convertingHammers()) continue;
            double perSecond = BuildMath.perSecond(module.integrations().hammersPerHour(town), cfg.speedMultiplier());
            boolean normalActive = false;
            for (Structure s : e.getValue()) if (!s.typeDef().isWonder()) normalActive = true;
            for (Structure s : e.getValue()) {
                if (cfg.pauseWonderWhileBuilding() && s.typeDef().isWonder() && normalActive) continue;
                advance(s, town, perSecond);
            }
        }
        for (Map.Entry<String, List<Structure>> e : repairing.entrySet()) {
            Town town = civ.state().town(e.getKey());
            if (town == null || town.convertingHammers()) continue;
            double perSecond = BuildMath.repairPerSecond(module.integrations().hammersPerHour(town), e.getValue().size());
            for (Structure s : e.getValue()) {
                if (s.removed() || !s.repairing()) continue;
                s.repairDone(s.repairDone() + perSecond);
                s.dirty(true);
                if (s.repairDone() >= s.repairRequired()) module.finishRepair(s);
            }
        }
        regenerate();
        boolean save = seconds % cfg.saveIntervalSeconds() == 0;
        for (Structure s : module.index().all()) {
            if (s.dirty() && (save || !s.isBuilding())) module.save(s);
        }
        updateDisplays(building, repairing);
    }

    private void advance(Structure s, Town town, double perSecond) {
        if (perSecond <= 0 || s.removed() || !s.isBuilding()) return;
        s.hammersDone(Math.min(s.hammersRequired(), s.hammersDone() + perSecond));
        s.dirty(true);
        pasting.add(s);
        int step = module.settings().announceStep();
        int percent = (int) Math.floor(s.progress() * 100);
        if (percent / step > s.announcedPercent() / step && percent < 100) {
            s.announcedPercent(percent - percent % step);
            StructureType type = s.typeDef();
            if (type.isWorldWonder()) {
                module.broadcast("structure.wonder.progress", arg("name", type.name()), arg("town", town.name()), arg("percent", percent));
            } else {
                module.tellTown(town, "structure.progress", arg("name", type.name()), arg("percent", percent),
                        arg("bar", Format.progressBar(s.progress(), 20)));
            }
        }
        if (s.hammersDone() >= s.hammersRequired()) module.complete(s);
    }

    private void regenerate() {
        if (damaged.isEmpty()) return;
        boolean war = module.warActive();
        for (Iterator<Structure> it = damaged.iterator(); it.hasNext(); ) {
            Structure s = it.next();
            if (s.removed() || s.isDestroyed() || s.hp() >= s.maxHp()) {
                it.remove();
                continue;
            }
            StructureType type = s.typeDef();
            if (type.regen() <= 0 || (war && !type.warRegen())) continue;
            s.hp((int) Math.min(s.maxHp(), s.hp() + Math.max(1, Math.round(type.regen()))));
            s.dirty(true);
        }
    }

    void markDamaged(Structure s) {
        damaged.add(s);
    }

    // --- boss bars and holograms ----------------------------------------------------------------------------------

    private void updateDisplays(Map<String, List<Structure>> building, Map<String, List<Structure>> repairing) {
        StructureSettings cfg = module.settings();
        Set<String> active = new HashSet<>();
        List<Structure> shown = new ArrayList<>();
        building.values().forEach(shown::addAll);
        repairing.values().forEach(shown::addAll);
        double r2 = cfg.bossbarRadius() * cfg.bossbarRadius();
        for (Structure s : shown) {
            if (s.removed() || (!s.isBuilding() && !s.repairing())) continue;
            Town town = civ.state().town(s.townId());
            if (town == null) continue;
            active.add(s.id());
            boolean repair = s.repairing();
            double progress = repair ? (s.repairRequired() <= 0 ? 1 : s.repairDone() / s.repairRequired()) : s.progress();
            double remaining = repair ? s.repairRequired() - s.repairDone() : s.hammersRequired() - s.hammersDone();
            double hph = module.integrations().hammersPerHour(town);
            long eta = repair
                    ? (long) (remaining <= 0 ? 0 : (hph <= 0 ? -1 : remaining / BuildMath.repairPerSecond(hph, repairing.get(s.townId()).size())))
                    : BuildMath.secondsRemaining(remaining, hph, cfg.speedMultiplier());
            String etaText = eta < 0 ? civ.messages().plain("structure.eta-never") : Durations.format(Duration.ofSeconds(eta));
            Component title = civ.messages().component(repair ? "structure.bossbar.repair" : "structure.bossbar.build",
                    arg("name", s.typeDef().name()), arg("percent", (int) Math.floor(progress * 100)), arg("eta", etaText));
            BossBar bar = bars.computeIfAbsent(s.id(), k -> BossBar.bossBar(title, 0f,
                    s.typeDef().isWonder() ? BossBar.Color.PURPLE : BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20));
            bar.name(title);
            bar.progress((float) Math.max(0, Math.min(1, progress)));
            Set<UUID> seen = viewers.computeIfAbsent(s.id(), k -> new HashSet<>());
            BlockPos center = s.center();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Resident res = civ.state().resident(p);
                boolean show = res != null && town.id().equals(res.townId()) && p.getWorld().getName().equals(center.world())
                        && p.getLocation().distanceSquared(new Location(p.getWorld(), center.x(), p.getLocation().getY(), center.z())) <= r2;
                if (show && seen.add(p.getUniqueId())) p.showBossBar(bar);
                else if (!show && seen.remove(p.getUniqueId())) p.hideBossBar(bar);
            }
            if (cfg.progressHologram() && seconds % 2 == 0) {
                World w = center.bukkitWorld();
                if (w != null && w.isChunkLoaded(center.x() >> 4, center.z() >> 4)) {
                    Location at = new Location(w, center.x() + 0.5, s.origin().y() + Math.min(s.sizeY(), 12) + 1.5, center.z() + 0.5);
                    Holograms.show("build:" + s.id(), at, title);
                }
            }
        }
        for (String id : List.copyOf(bars.keySet())) {
            if (!active.contains(id)) hide(id);
        }
    }

    private void hide(String id) {
        BossBar bar = bars.remove(id);
        Set<UUID> seen = viewers.remove(id);
        if (bar != null && seen != null) {
            for (UUID uuid : seen) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.hideBossBar(bar);
            }
        }
        Holograms.remove("build:" + id);
    }

    /** Stops tracking a structure (completed, removed): hides its displays. */
    void forget(Structure s) {
        hide(s.id());
        if (s.removed()) {
            pasting.remove(s);
            damaged.remove(s);
        }
    }

    void shutdown() {
        for (String id : List.copyOf(bars.keySet())) hide(id);
    }

    // --- block placement --------------------------------------------------------------------------------------------

    void tickBlocks() {
        int budget = module.settings().blocksPerTick();
        if (!pasting.isEmpty()) {
            int share = Math.max(16, budget / pasting.size());
            for (Iterator<Structure> it = pasting.iterator(); it.hasNext() && budget > 0; ) {
                Structure s = it.next();
                if (s.removed() || s.template() == null) {
                    it.remove();
                    continue;
                }
                budget -= paste(s, Math.min(share, budget));
                if (!s.isBuilding() && s.cursor() >= s.cellCount()) {
                    it.remove();
                    if (!s.markersBuilt()) module.blocksReady(s);
                }
            }
        }
        module.jobs().tick(budget);
    }

    /** Pastes up to {@code budget} changed blocks of the structure; returns the budget used. */
    private int paste(Structure s, int budget) {
        Template t = s.template();
        World w = Bukkit.getWorld(s.origin().world());
        if (w == null || t == null) return 0;
        int total = s.cellCount();
        int target = s.isBuilding() ? BuildMath.targetCells(total, s.progress()) : total;
        int sx = s.sizeX();
        int sz = s.sizeZ();
        BlockPos o = s.origin();
        int used = 0;
        int scanned = 0;
        int cell = s.cursor();
        while (cell < target && used < budget) {
            int x = cell % sx;
            int z = (cell / sx) % sz;
            int y = cell / (sx * sz);
            int bx = o.x() + x;
            int bz = o.z() + z;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) break;
            int by = o.y() + y;
            if (by >= w.getMinHeight() && by < w.getMaxHeight()) {
                String previous = BlockWork.paste(w.getBlockAt(bx, by, bz), t, x, y, z);
                if (previous != null) {
                    if (s.undo() != null) s.undo().capture(cell, previous);
                    used++;
                }
            }
            if (++scanned % 16 == 0) used++;
            cell++;
        }
        if (cell != s.cursor()) {
            s.cursor(cell);
            s.dirty(true);
        }
        return used;
    }

    /** Pastes everything that is loaded right now (instant / admin completion). */
    void pasteNow(Structure s) {
        if (s.template() == null) {
            s.markersBuilt(true);
            return;
        }
        paste(s, Integer.MAX_VALUE);
        if (!s.isBuilding() && s.cursor() >= s.cellCount()) {
            pasting.remove(s);
            if (!s.markersBuilt()) module.blocksReady(s);
        } else {
            pasting.add(s);
        }
    }

    // --- sweeps -----------------------------------------------------------------------------------------------------

    /** Re-places every block that differs from the template, then rebuilds the markers. */
    void refresh(Structure s) {
        Template t = s.template();
        if (t == null) return;
        s.markersBuilt(false);
        module.jobs().add(new SweepJob(s.origin(), s.sizeX(), s.sizeY(), s.sizeZ(),
                (block, x, y, z, cell) -> BlockWork.paste(block, t, x, y, z) != null,
                s::removed, () -> module.blocksReady(s)));
    }

    /** Destroyed structure: part of its blocks turn into rubble, some burn (spec 02 §1.6). */
    void rubble(Structure s) {
        Template t = s.template();
        if (t == null) return;
        StructureSettings cfg = module.settings();
        Material rubble = cfg.rubble();
        double share = cfg.rubbleShare();
        int surface = Math.max(1, s.sizeX() * s.sizeZ());
        double fireChance = Math.min(1, cfg.fireBlocks() / (double) surface);
        long seed = s.id().hashCode();
        Random r = new Random(seed);
        module.jobs().add(new SweepJob(s.origin(), s.sizeX(), s.sizeY(), s.sizeZ(), (block, x, y, z, cell) -> {
            if (y == 0 || !s.isDestroyed()) return false;
            Material m = block.getType();
            if (m.isAir() || !m.isSolid() || isFunctional(m)) return false;
            if (t.block(x, y, z).getMaterial().isAir()) return false;
            boolean changed = false;
            if (r.nextDouble() < share) {
                block.setType(rubble, false);
                changed = true;
            }
            Block above = block.getRelative(0, 1, 0);
            if (above.getType().isAir() && (y + 1 >= t.sizeY() || t.block(x, y + 1, z).getMaterial().isAir())
                    && r.nextDouble() < fireChance) {
                above.setType(Material.FIRE, false);
                changed = true;
            }
            return changed;
        }, () -> s.removed() || !s.isDestroyed(), null));
    }

    private static boolean isFunctional(Material m) {
        return Tag.ALL_SIGNS.isTagged(m) || Tag.DOORS.isTagged(m) || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.BARREL || Tag.SHULKER_BOXES.isTagged(m) || m == Material.FURNACE || m == Material.BLAST_FURNACE
                || m == Material.SMOKER || m == Material.ENDER_CHEST || m == Material.BEDROCK || Tag.BEDS.isTagged(m);
    }
}
