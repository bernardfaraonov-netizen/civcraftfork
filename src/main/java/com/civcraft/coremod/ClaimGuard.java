package com.civcraft.coremod;

import com.civcraft.core.util.ChunkKey;
import com.civcraft.model.Claim;
import com.civcraft.model.PlotPerm;
import com.civcraft.model.PlotSubject;
import com.civcraft.model.Resident;
import com.civcraft.model.Town;
import com.civcraft.protection.Action;
import com.civcraft.protection.Guard;
import com.civcraft.protection.Verdict;
import com.civcraft.state.GameState;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Plot permissions (spec §7.4): others → owner/officials → plot groups → civ. Environment actions
 * crossing from outside into a claim (pistons, liquids, fire) are denied; explosions inside claims are
 * denied in peace time (the war module overrides this with a higher-priority guard).
 */
public final class ClaimGuard implements Guard {

    public static final int PRIORITY = 500;

    private final GameState state;

    public ClaimGuard(GameState state) {
        this.state = state;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Verdict check(Player actor, Action action, Block block, Block source) {
        Claim claim = state.claim(ChunkKey.of(block));
        if (actor == null) return environment(claim, action, block, source);
        if (claim == null) return Verdict.PASS;
        PlotPerm perm = switch (action) {
            case BREAK -> PlotPerm.DESTROY;
            case PLACE -> PlotPerm.BUILD;
            case INTERACT, ENTITY -> PlotPerm.INTERACT;
            case ITEMUSE, FIRE, FLOW -> PlotPerm.ITEMUSE;
        };
        return allowed(claim, actor.getUniqueId(), perm) ? Verdict.PASS : Verdict.deny("protection.claim");
    }

    public boolean allowed(Claim claim, UUID player, PlotPerm perm) {
        if (claim.allowed(PlotSubject.OTHERS, perm)) return true;
        Town town = state.town(claim.townId());
        if (town == null) return true;
        boolean owner = claim.owner() != null ? claim.owner().equals(player) : town.isOfficial(player);
        if (owner && claim.allowed(PlotSubject.OWNER, perm)) return true;
        if (claim.allowed(PlotSubject.GROUP, perm)) {
            for (String group : claim.groups()) {
                if (town.group(group).contains(player)) return true;
            }
        }
        if (claim.allowed(PlotSubject.CIV, perm)) {
            Resident r = state.resident(player);
            Town playerTown = state.townOf(r);
            if (playerTown != null && !playerTown.isCaptured() && playerTown.civId() != null
                    && playerTown.civId().equals(town.civId())) {
                return true;
            }
        }
        return false;
    }

    private Verdict environment(Claim claim, Action action, Block block, Block source) {
        if (claim == null) return Verdict.PASS;
        switch (action) {
            case FLOW, FIRE -> {
                if (source == null) return action == Action.FIRE && !claim.fire() ? Verdict.deny(null) : Verdict.PASS;
                Claim from = state.claim(ChunkKey.of(source));
                if (from == null || !from.townId().equals(claim.townId())) return Verdict.deny(null);
                if (action == Action.FIRE && !claim.fire()) return Verdict.deny(null);
                return Verdict.PASS;
            }
            case BREAK, ENTITY -> {
                return Verdict.deny(null);
            }
            default -> {
                return Verdict.PASS;
            }
        }
    }
}
