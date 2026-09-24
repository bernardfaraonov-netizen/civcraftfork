package com.civcraft.item;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Reads and writes the per-stack CivCraft data kept in the persistent data container. */
public final class ItemData {

    private ItemData() {
    }

    public static boolean empty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** Custom item id or null. */
    public static String id(ItemStack stack) {
        if (empty(stack)) return null;
        return stack.getPersistentDataContainer().get(ItemKeys.ITEM, PersistentDataType.STRING);
    }

    public static int revision(ItemStack stack) {
        Integer rev = stack.getPersistentDataContainer().get(ItemKeys.REVISION, PersistentDataType.INTEGER);
        return rev == null ? 0 : rev;
    }

    public static int sharpen(ItemStack stack) {
        if (empty(stack)) return 0;
        Integer level = stack.getPersistentDataContainer().get(ItemKeys.SHARPEN, PersistentDataType.INTEGER);
        return level == null ? 0 : Math.max(0, level);
    }

    public static void sharpen(ItemStack stack, int level) {
        stack.editPersistentDataContainer(pdc -> {
            if (level <= 0) pdc.remove(ItemKeys.SHARPEN);
            else pdc.set(ItemKeys.SHARPEN, PersistentDataType.INTEGER, level);
        });
    }

    public static boolean soulbound(ItemStack stack) {
        if (empty(stack)) return false;
        return Boolean.TRUE.equals(stack.getPersistentDataContainer().get(ItemKeys.SOULBOUND, PersistentDataType.BOOLEAN));
    }

    public static void soulbound(ItemStack stack, boolean value) {
        stack.editPersistentDataContainer(pdc -> {
            if (value) pdc.set(ItemKeys.SOULBOUND, PersistentDataType.BOOLEAN, true);
            else pdc.remove(ItemKeys.SOULBOUND);
        });
    }

    public static boolean singleUse(ItemStack stack) {
        if (empty(stack)) return false;
        Byte b = stack.getPersistentDataContainer().get(ItemKeys.SINGLE_USE, PersistentDataType.BYTE);
        return b != null && b != 0;
    }

    public static void markSingleUse(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(ItemKeys.SINGLE_USE, PersistentDataType.BYTE, (byte) 1));
    }

    /** Custom enchantment effect id → level. */
    public static Map<String, Integer> customEnchants(ItemStack stack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (empty(stack)) return result;
        PersistentDataContainer container = stack.getPersistentDataContainer()
                .get(ItemKeys.ENCHANTS, PersistentDataType.TAG_CONTAINER);
        if (container == null) return result;
        for (NamespacedKey key : container.getKeys()) {
            Integer level = container.get(key, PersistentDataType.INTEGER);
            if (level != null && level > 0) result.put(key.getKey(), level);
        }
        return result;
    }

    public static int customEnchant(ItemStack stack, String effect) {
        return customEnchants(stack).getOrDefault(effect, 0);
    }

    public static void customEnchant(ItemStack stack, String effect, int level) {
        stack.editPersistentDataContainer(pdc -> {
            PersistentDataContainer container = pdc.get(ItemKeys.ENCHANTS, PersistentDataType.TAG_CONTAINER);
            if (container == null) container = pdc.getAdapterContext().newPersistentDataContainer();
            NamespacedKey key = ItemKeys.key(effect);
            if (level <= 0) container.remove(key);
            else container.set(key, PersistentDataType.INTEGER, level);
            if (container.isEmpty()) pdc.remove(ItemKeys.ENCHANTS);
            else pdc.set(ItemKeys.ENCHANTS, PersistentDataType.TAG_CONTAINER, container);
        });
    }
}
