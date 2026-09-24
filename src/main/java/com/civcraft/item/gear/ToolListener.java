package com.civcraft.item.gear;

import com.civcraft.item.ItemRegistry;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;

/**
 * Durability in normal use: CivCraft gear (T4 tungsten tools included) never wears out while fighting
 * or digging, only on death (spec 04 §5.5, §8.2). The «Укреплённые инструменты» artifact chance for other
 * tools is applied by the artifact module.
 */
public final class ToolListener implements Listener {

    private final ItemRegistry registry;

    public ToolListener(ItemRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemDef def = registry.def(event.getItem());
        if (def != null && (def.has(ItemFlag.UNBREAKABLE) || (def.isGear() && !def.has(ItemFlag.VANILLA_DURABILITY)))) {
            event.setCancelled(true);
        }
    }
}
