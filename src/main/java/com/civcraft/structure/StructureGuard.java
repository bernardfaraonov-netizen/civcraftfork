package com.civcraft.structure;

import com.civcraft.CivCraft;
import com.civcraft.core.util.BlockPos;
import com.civcraft.model.Town;
import com.civcraft.protection.Action;
import com.civcraft.protection.Guard;
import com.civcraft.protection.Verdict;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;

/**
 * Structure blocks can only be changed by the plugin (spec 01 §6.4 "Структурные блоки защищены всегда"). Runs before
 * the claim guard; the war module registers a guard with a lower priority number to allow war damage.
 * Construction sites are closed while building. Containers and doors follow {@code interact:} in the balance file.
 */
final class StructureGuard implements Guard {

    static final int PRIORITY = 400;
    private static final Verdict DENY_STRUCTURE = Verdict.deny("protection.structure");
    private static final Verdict DENY_SITE = Verdict.deny("structure.protect.construction");
    private static final Verdict DENY_USE = Verdict.deny("structure.protect.use");
    private static final Verdict DENY_SILENT = Verdict.deny(null);

    private final StructureModule module;
    private final CivCraft civ;

    StructureGuard(StructureModule module, CivCraft civ) {
        this.module = module;
        this.civ = civ;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Verdict check(Player actor, Action action, Block block, Block source) {
        BlockPos pos = BlockPos.of(block);
        Structure s = module.index().at(pos);
        if (s == null || s.removed()) return Verdict.PASS;
        boolean building = s.isBuilding();
        if (s.typeDef().destroyable() && !building) return Verdict.PASS;
        boolean structural = s.isStructureBlock(pos);
        if (actor == null) {
            return switch (action) {
                case BREAK, FLOW, FIRE -> structural || building ? DENY_SILENT : Verdict.PASS;
                default -> Verdict.PASS;
            };
        }
        return switch (action) {
            case BREAK, PLACE -> structural ? DENY_STRUCTURE : building ? DENY_SITE : Verdict.PASS;
            case ITEMUSE, FIRE, FLOW -> structural || building ? DENY_STRUCTURE : Verdict.PASS;
            case INTERACT -> interact(actor, block, s);
            case ENTITY -> allowed(actor, module.town(s), "town") ? Verdict.PASS : DENY_USE;
        };
    }

    private Verdict interact(Player actor, Block block, Structure s) {
        Material m = block.getType();
        String policy;
        if (block.getState(false) instanceof Container || m == Material.ENDER_CHEST) policy = module.settings().interactContainers();
        else if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) policy = module.settings().interactDoors();
        else policy = module.settings().interactOther();
        return allowed(actor, module.town(s), policy) ? Verdict.PASS : DENY_USE;
    }

    private boolean allowed(Player actor, Town town, String policy) {
        if (town == null) return true;
        return switch (policy) {
            case "town" -> module.isMember(actor, town) || module.integrations().canManage(actor, town);
            case "civ" -> module.isMember(actor, town) || module.isCivMember(actor, town);
            default -> true;
        };
    }
}
