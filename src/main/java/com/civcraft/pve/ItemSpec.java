package com.civcraft.pve;

import com.civcraft.CivCraft;
import com.civcraft.core.text.Messages;
import com.civcraft.item.ItemApi;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * One configurable item of a loot table. Balance files describe items as maps:
 * <pre>
 * - {item: mat_forged_clay, chance: 10}                 # custom item (ItemApi id), 10 %
 * - {item: bone, chance: 10, min: 1, max: 3}              # vanilla material
 * - {item: diamond_pickaxe, enchants: {efficiency: 3}}    # vanilla with enchantments
 * - {item: potion, potion: strong_healing}                # potion type
 * </pre>
 * Custom ids are created through {@link ItemApi}; when the item module is missing or does not know
 * the id, the entry is skipped (and logged once) instead of producing a fake item.
 */
public record ItemSpec(String item, double chance, int min, int max, Map<String, Integer> enchants,
                       String potion, String name, boolean unbreakable, Map<String, Object> extra) {

    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    /** Parses a list of maps; malformed entries are logged and skipped. */
    public static List<ItemSpec> parseList(List<?> raw, Logger logger, String where) {
        List<ItemSpec> result = new ArrayList<>();
        if (raw == null) return result;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> map)) continue;
            ItemSpec spec = parse(map, logger, where);
            if (spec != null) result.add(spec);
        }
        return result;
    }

    public static ItemSpec parse(Map<?, ?> map, Logger logger, String where) {
        Object id = map.get("item");
        if (id == null) {
            logger.warning("Loot entry without 'item' in " + where + ": " + map);
            return null;
        }
        double chance = number(map.get("chance"), 100);
        int min = (int) number(map.get("min"), map.containsKey("amount") ? number(map.get("amount"), 1) : 1);
        int max = (int) number(map.get("max"), map.containsKey("amount") ? number(map.get("amount"), 1) : min);
        if (chance < 0 || !Double.isFinite(chance) || min < 0 || max < min) {
            logger.warning("Invalid loot entry in " + where + ": " + map);
            return null;
        }
        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (map.get("enchants") instanceof Map<?, ?> e) {
            for (Map.Entry<?, ?> en : e.entrySet()) {
                enchants.put(en.getKey().toString().toLowerCase(Locale.ROOT), (int) number(en.getValue(), 1));
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : map.entrySet()) extra.put(en.getKey().toString(), en.getValue());
        return new ItemSpec(id.toString(), chance, min, max, enchants,
                map.get("potion") == null ? null : map.get("potion").toString(),
                map.get("name") == null ? null : map.get("name").toString(),
                Boolean.TRUE.equals(map.get("unbreakable")), extra);
    }

    private static double number(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Rolls the chance (percent). */
    public boolean roll() {
        return chance >= 100 || ThreadLocalRandom.current().nextDouble(100) < chance;
    }

    public boolean roll(double multiplier) {
        double c = chance * multiplier;
        return c >= 100 || ThreadLocalRandom.current().nextDouble(100) < c;
    }

    public int rollAmount() {
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public String string(String key) {
        Object o = extra.get(key);
        return o == null ? null : o.toString();
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(extra.get(key));
    }

    /** Builds a stack with a random amount; null when the amount is 0 or the item is unavailable. */
    public ItemStack build() {
        return build(rollAmount());
    }

    public ItemStack build(int amount) {
        if (amount <= 0) return null;
        ItemStack stack = create(item, amount);
        if (stack == null) return null;
        if (!enchants.isEmpty() || potion != null || name != null || unbreakable) {
            ItemMeta meta = stack.getItemMeta();
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                Enchantment ench = enchantment(e.getKey());
                if (ench != null) meta.addEnchant(ench, e.getValue(), true);
                else warnOnce("enchant:" + e.getKey(), "Unknown enchantment '" + e.getKey() + "' in loot of " + item);
            }
            if (potion != null && meta instanceof PotionMeta pm) {
                PotionType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.POTION)
                        .get(Key.key(potion.toLowerCase(Locale.ROOT)));
                if (type != null) pm.setBasePotionType(type);
                else warnOnce("potion:" + potion, "Unknown potion type '" + potion + "'");
            }
            if (name != null) {
                meta.displayName(Messages.get().parse(name).decoration(TextDecoration.ITALIC, false));
            }
            if (unbreakable) meta.setUnbreakable(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static Enchantment enchantment(String key) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .get(Key.key(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Creates an item by id: a vanilla material ({@code bone}, {@code minecraft:bone}) or a custom item
     * id known to the item module. Returns null (logged once) when unavailable.
     */
    public static ItemStack create(String id, int amount) {
        if (amount <= 0) return null;
        if (id.startsWith(PveItems.PREFIX)) {
            ItemStack pve = PveItems.create(id.substring(PveItems.PREFIX.length()), amount);
            if (pve == null) warnOnce("pve:" + id, "Unknown PvE item '" + id + "'");
            return pve;
        }
        Material material = id.startsWith("minecraft:") ? vanilla(id) : null;
        if (material != null) return new ItemStack(material, amount);
        ItemApi items = CivCraft.get().apiOrNull(ItemApi.class);
        if (items != null && items.exists(id)) return items.create(id, amount);
        material = vanilla(id);
        if (material != null) return new ItemStack(material, amount);
        warnOnce("unknown:" + id, items == null
                ? "Item module is not installed; skipping custom item '" + id + "'"
                : "Unknown item id '" + id + "' in a PvE loot table; skipping");
        return null;
    }

    /** Whether the stack matches the id (vanilla material or custom item id). */
    public static boolean matches(ItemStack stack, String id) {
        if (stack == null || stack.getType().isAir()) return false;
        if (id.startsWith(PveItems.PREFIX)) return id.substring(PveItems.PREFIX.length()).equals(PveItems.id(stack));
        String custom = customId(stack);
        if (custom != null) return custom.equals(id);
        Material material = vanilla(id);
        return material != null && stack.getType() == material;
    }

    /** Custom item id of the stack (via ItemApi, else the raw PDC key), or null. */
    public static String customId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        ItemApi items = CivCraft.get().apiOrNull(ItemApi.class);
        if (items != null) return items.id(stack);
        if (!stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(PveKeys.ITEM_ID,
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    private static Material vanilla(String id) {
        Material m = Material.matchMaterial(id);
        return m != null && m.isItem() && !m.isAir() ? m : null;
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) CivCraft.get().logger().warning(message);
    }
}
