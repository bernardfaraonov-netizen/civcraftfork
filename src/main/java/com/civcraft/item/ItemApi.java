package com.civcraft.item;

import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Custom items. Every CivCraft item carries its id in the PDC key {@code civcraft:item}; identification
 * never looks at names or lore. Implemented by the item module.
 */
public interface ItemApi {

    /** Creates a stack of a registered custom item; throws for unknown ids. */
    ItemStack create(String id, int amount);

    /** Custom item id of the stack, or null for vanilla items. */
    String id(ItemStack stack);

    default boolean is(ItemStack stack, String id) {
        return stack != null && id.equals(id(stack));
    }

    boolean exists(String id);

    /** Display name of an item id (custom or vanilla material key) for messages. */
    net.kyori.adventure.text.Component displayName(String id);

    /**
     * Registers what happens when a player right-clicks with the item (e.g. camp door, founding flag,
     * settler). The event is cancelled before the handler runs.
     */
    void onUse(String id, BiConsumer<Player, PlayerInteractEvent> handler);

    /** Counts items of the given id (custom id or vanilla material key like "minecraft:coal") in the inventory. */
    int count(Player player, String id);

    /** Removes exactly {@code amount} items; returns false and removes nothing if there are not enough. */
    boolean take(Player player, String id, int amount);

    /** Gives items, dropping what does not fit at the player's feet. */
    void give(Player player, ItemStack stack);

    /** Marks a stack soulbound (kept on death). */
    void soulbind(ItemStack stack, boolean soulbound);
}
