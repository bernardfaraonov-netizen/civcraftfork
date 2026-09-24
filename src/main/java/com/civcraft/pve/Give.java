package com.civcraft.pve;

import com.civcraft.CivCraft;
import com.civcraft.item.ItemApi;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Gives items to players, dropping what does not fit at their feet. */
public final class Give {

    private Give() {
    }

    public static void give(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;
        ItemApi items = CivCraft.get().apiOrNull(ItemApi.class);
        if (items != null) {
            items.give(player, stack);
            return;
        }
        for (ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
