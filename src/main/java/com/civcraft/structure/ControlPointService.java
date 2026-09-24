package com.civcraft.structure;

import static com.civcraft.core.text.Messages.arg;

import com.civcraft.CivCraft;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.ui.Holograms;
import com.civcraft.core.util.BlockPos;
import com.civcraft.model.Town;
import com.civcraft.structure.component.StructureComponent;
import com.civcraft.structure.event.ControlPointDamageEvent;
import com.civcraft.structure.event.ControlPointDestroyedEvent;
import com.civcraft.structure.event.TownControlLostEvent;
import com.civcraft.structure.type.ControlDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Control blocks of town halls, capitols, war camps and Neuschwanstein (spec 01 §5.5, §6.2; 02 §2.4). The war module
 * calls {@link #damage}; this service keeps HP, holograms, block visuals and fires the events. When the last control
 * block of a town breaks, {@link TownControlLostEvent} is fired and the war module captures the town.
 */
public final class ControlPointService {

    private final StructureModule module;
    private final CivCraft civ;

    ControlPointService(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    /** Creates the control points from the {@code /control} markers when the structure completes. */
    void create(Structure s) {
        s.controlPoints().clear();
        int hp = maxHp(s);
        for (StructureComponent c : s.components("control")) s.controlPoints().add(new ControlPoint(c.index(), c.pos(), hp));
    }

    /** Current maximum HP of the structure's control blocks: era formula + stat modifiers (castles, talents...). */
    public int maxHp(Structure s) {
        ControlDef def = s.typeDef().control();
        if (def == null) return 0;
        Town town = module.town(s);
        ControlDef base = def;
        if (def.inheritMain() && town != null) {
            Structure main = module.mainBuilding(town);
            if (main != null && main.typeDef().control() != null) base = main.typeDef().control();
        }
        int era = town == null ? 0 : module.integrations().era(civ.state().civOf(town));
        double hp = base.baseHp(era);
        if (town != null) hp = civ.stats().town(town, def.stat(), hp);
        return (int) Math.max(1, Math.round(hp));
    }

    public List<ControlPoint> of(Structure s) {
        return Collections.unmodifiableList(s.controlPoints());
    }

    /** Every control point that defends the town (main building, Neuschwanstein...). */
    public List<ControlPoint> of(Town town) {
        List<ControlPoint> list = new ArrayList<>();
        for (Structure s : module.index().town(town.id())) {
            if (!s.isBuilding() && !s.removed()) list.addAll(s.controlPoints());
        }
        return list;
    }

    /** The control point at a block, or null. */
    public ControlPoint at(BlockPos pos) {
        Structure s = module.index().at(pos);
        if (s == null) return null;
        for (ControlPoint cp : s.controlPoints()) if (cp.pos().equals(pos)) return cp;
        return null;
    }

    /** The structure owning the control point at a block, or null. */
    public Structure ownerAt(BlockPos pos) {
        Structure s = module.index().at(pos);
        return s != null && at(pos) != null ? s : null;
    }

    public boolean allDestroyed(Town town) {
        List<ControlPoint> points = of(town);
        if (points.isEmpty()) return false;
        for (ControlPoint cp : points) if (!cp.destroyed()) return false;
        return true;
    }

    /** Damages a control block; returns the remaining HP. */
    public int damage(Structure s, ControlPoint cp, int amount, Player attacker) {
        Tasks.checkMain();
        if (s.removed() || cp.destroyed() || amount <= 0) return cp.hp();
        ControlPointDamageEvent event = new ControlPointDamageEvent(s.id(), s.townId(), cp.index(), attacker, amount).call();
        if (event.isCancelled() || event.amount() <= 0) return cp.hp();
        cp.hp(cp.hp() - event.amount());
        module.save(s);
        Town town = module.town(s);
        Block block = block(cp);
        if (block != null) {
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
            block.getWorld().spawnParticle(Particle.CRIT, at, 12, 0.4, 0.4, 0.4, 0.1);
        }
        if (attacker != null) {
            civ.messages().actionBar(attacker, "structure.control.hit", arg("hp", cp.hp()), arg("max", cp.maxHp()));
        }
        if (cp.destroyed()) {
            showBlock(s, cp);
            if (town != null) module.tellTown(town, "structure.control.destroyed", arg("name", s.typeDef().name()), module.coords(s));
            new ControlPointDestroyedEvent(s.id(), s.townId(), cp.index(), attacker).call();
            if (town != null && allDestroyed(town)) new TownControlLostEvent(town.id(), s.id(), attacker).call();
        } else if (town != null) {
            module.tellTown(town, "structure.control.damaged", arg("name", s.typeDef().name()), arg("hp", cp.hp()),
                    arg("max", cp.maxHp()));
        }
        hologram(s, cp);
        return cp.hp();
    }

    public void repair(Structure s, ControlPoint cp) {
        Tasks.checkMain();
        cp.hp(cp.maxHp());
        module.save(s);
        showBlock(s, cp);
        hologram(s, cp);
    }

    public void repairAll(Structure s) {
        for (ControlPoint cp : s.controlPoints()) repair(s, cp);
    }

    /** Recomputes max HP (spec: "В начале войны прочность всех КБ пересчитывается"); intact blocks are healed. */
    public void recalculate(Town town) {
        for (Structure s : module.index().town(town.id())) {
            if (s.controlPoints().isEmpty()) continue;
            int max = maxHp(s);
            for (ControlPoint cp : s.controlPoints()) {
                cp.maxHp(max);
                if (!cp.destroyed()) cp.hp(max);
                hologram(s, cp);
            }
            module.save(s);
        }
    }

    public void recalculateAll() {
        for (Town town : civ.state().towns()) recalculate(town);
    }

    /** Shows holograms for loaded control blocks. */
    void refresh(Structure s) {
        for (ControlPoint cp : s.controlPoints()) hologram(s, cp);
    }

    void forget(Structure s) {
        for (ControlPoint cp : s.controlPoints()) Holograms.remove(id(s, cp));
    }

    private static String id(Structure s, ControlPoint cp) {
        return "cp:" + s.id() + ":" + cp.index();
    }

    private Block block(ControlPoint cp) {
        World w = cp.pos().bukkitWorld();
        if (w == null || !w.isChunkLoaded(cp.pos().x() >> 4, cp.pos().z() >> 4)) return null;
        return w.getBlockAt(cp.pos().x(), cp.pos().y(), cp.pos().z());
    }

    /** Places the control block (intact or broken look) if the chunk is loaded. */
    void showBlock(Structure s, ControlPoint cp) {
        Block b = block(cp);
        if (b == null) return;
        StructureSettings cfg = module.settings();
        b.setType(cp.destroyed() ? cfg.controlDestroyed() : cfg.controlBlock(), false);
    }

    private void hologram(Structure s, ControlPoint cp) {
        World w = cp.pos().bukkitWorld();
        if (w == null) return;
        Location at = new Location(w, cp.pos().x() + 0.5, cp.pos().y() + module.settings().controlHologramHeight(), cp.pos().z() + 0.5);
        Component text = cp.destroyed()
                ? civ.messages().component("structure.control.hologram-destroyed")
                : civ.messages().component("structure.control.hologram", arg("hp", cp.hp()), arg("max", cp.maxHp()));
        Holograms.show(id(s, cp), at, text);
    }
}
