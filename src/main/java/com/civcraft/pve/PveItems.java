package com.civcraft.pve;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Items owned by the PvE modules (lucky block, bonus scrolls, recipe scrolls, ruin weapons). They are
 * referenced in loot tables as {@code pve:<id>} and identified by the PDC key {@code civcraft:pve_item},
 * separate from the item module's {@code civcraft:item} registry.
 */
public final class PveItems {

    public static final String PREFIX = "pve:";
    private static final Map<String, IntFunction<ItemStack>> FACTORIES = new ConcurrentHashMap<>();

    private PveItems() {
    }

    public static void register(String id, IntFunction<ItemStack> factory) {
        FACTORIES.put(id, factory);
    }

    public static Set<String> ids() {
        return FACTORIES.keySet();
    }

    public static boolean exists(String id) {
        return FACTORIES.containsKey(id);
    }

    public static ItemStack create(String id, int amount) {
        IntFunction<ItemStack> f = FACTORIES.get(id);
        return f == null || amount <= 0 ? null : f.apply(amount);
    }

    /** PvE item id of the stack or null. */
    public static String id(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(PveKeys.PVE_ITEM, PersistentDataType.STRING);
    }
}
