package com.civcraft.item.def;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * Static definition of a custom item, loaded from balance YAML.
 *
 * @param id             unique id stored in the PDC key {@code civcraft:item}
 * @param name           display name (MiniMessage, Russian)
 * @param material       base vanilla item
 * @param model          item_model key (namespaced), null = the base material's model
 * @param modelData      custom_model_data float for resource packs, or null
 * @param tier           0..4
 * @param kind           what the item is
 * @param category       recipe book category
 * @param rarity         rarity key (name color)
 * @param glint          enchantment glint override
 * @param stack          max stack size
 * @param lore           static lore lines (MiniMessage)
 * @param flags          behaviour switches
 * @param enchants       vanilla enchantments baked into the item (namespaced key → level)
 * @param customEnchants custom enchantments baked into the item (enchant effect id → level)
 * @param color          leather dye RGB or -1
 * @param tech           resolved science tech id needed to craft / use at full strength, or null
 * @param gear           combat stats for weapons, bows, armor and tools; null otherwise
 * @param data           free-form data for other modules (artifact prices, scroll parameters...)
 * @param singleUse      one-shot variant (artifacts from ruins)
 */
public record ItemDef(String id, String name, Material material, String model, Float modelData, int tier,
                      ItemKind kind, String category, String rarity, boolean glint, int stack, List<String> lore,
                      Set<ItemFlag> flags, Map<String, Integer> enchants, Map<String, Integer> customEnchants,
                      int color, String tech, GearStats gear, Map<String, String> data, boolean singleUse) {

    public boolean has(ItemFlag flag) {
        return flags.contains(flag);
    }

    public boolean isGear() {
        return gear != null;
    }

    public String data(String key, String def) {
        return data.getOrDefault(key, def);
    }

    public double dataDouble(String key, double def) {
        String value = data.get(key);
        if (value == null) return def;
        try {
            double d = Double.parseDouble(value);
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Kept on death: SoulBound, keep-on-death flag, artifacts and CivCraft gear (which loses durability
     * instead of dropping). Ruin tools with vanilla durability drop like vanilla tools.
     */
    public boolean keptOnDeath() {
        return has(ItemFlag.SOULBOUND) || has(ItemFlag.KEEP_ON_DEATH) || kind == ItemKind.ARTIFACT
                || (isGear() && !has(ItemFlag.VANILLA_DURABILITY));
    }

    /** Stable fingerprint of everything the renderer uses; changes when the YAML definition changes. */
    public int revision(int salt) {
        return 31 * toString().hashCode() + salt;
    }
}
