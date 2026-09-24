package com.civcraft.protection;

import com.civcraft.core.text.Messages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class ProtectionService {

    private final List<Guard> guards = new ArrayList<>();

    public void register(Guard guard) {
        guards.add(guard);
        guards.sort(Comparator.comparingInt(Guard::priority));
    }

    public Verdict check(Player actor, Action action, Block block, Block source) {
        if (actor != null && actor.hasPermission("civcraft.admin.bypass") && actor.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return Verdict.ALLOW;
        }
        for (Guard guard : guards) {
            Verdict v = guard.check(actor, action, block, source);
            if (v.kind() != Verdict.Kind.PASS) return v;
        }
        return Verdict.ALLOW;
    }

    /** Checks and, when denied, tells the player why (action bar, to avoid chat spam). */
    public boolean allowed(Player actor, Action action, Block block) {
        Verdict v = check(actor, action, block, null);
        if (v.denied()) {
            if (actor != null && v.messageKey() != null) Messages.get().actionBar(actor, v.messageKey());
            return false;
        }
        return true;
    }

    public boolean environmentAllowed(Action action, Block block, Block source) {
        return !check(null, action, block, source).denied();
    }
}
