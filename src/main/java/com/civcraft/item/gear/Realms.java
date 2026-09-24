package com.civcraft.item.gear;

import com.civcraft.CivCraft;
import com.civcraft.boss.ValleyApi;
import com.civcraft.dungeon.DungeonApi;
import com.civcraft.item.ItemRules;
import org.bukkit.entity.Player;

/**
 * Where a player is for gear rules: the Air Valley (only valley gear works, no Future Tech bonuses) and
 * the dungeon (halved heavy-armor speed penalty, soul armor sharpening cap +5). Asks the PvE module's
 * {@link ValleyApi} / {@link DungeonApi}; falls back to the world lists of balance/items.yml without it.
 */
public final class Realms {

    private final CivCraft civ;
    private final ItemRules rules;

    public Realms(CivCraft civ, ItemRules rules) {
        this.civ = civ;
        this.rules = rules;
    }

    public boolean inValley(Player player) {
        ValleyApi valley = civ.apiOrNull(ValleyApi.class);
        return valley != null ? valley.inValley(player) : rules.isValley(player.getWorld());
    }

    public boolean inDungeon(Player player) {
        DungeonApi dungeon = civ.apiOrNull(DungeonApi.class);
        return dungeon != null ? dungeon.inDungeon(player) : rules.isDungeon(player.getWorld());
    }
}
