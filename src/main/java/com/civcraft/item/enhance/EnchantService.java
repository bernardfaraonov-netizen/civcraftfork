package com.civcraft.item.enhance;

import com.civcraft.core.CivException;
import com.civcraft.item.ItemData;
import com.civcraft.item.ItemKeys;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.def.ArmorClass;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import com.civcraft.item.def.ItemKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Enchantment catalog and application rules (spec 04 §7). Library and wonder modules decide who may buy
 * and charge the price; this service checks whether the enchantment fits the item and applies it.
 */
public final class EnchantService {

    public static final String SOULBOUND = "soulbound";
    public static final String SKY_PUNISHMENT = "sky_punishment";

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final Logger logger;
    private final Map<String, EnchantDef> defs = new LinkedHashMap<>();
    private final Set<Material> soulboundForbidden = EnumSet.noneOf(Material.class);

    public EnchantService(ItemRegistry registry, ItemRenderer renderer, Logger logger) {
        this.registry = registry;
        this.renderer = renderer;
        this.logger = logger;
    }

    public void load(ConfigurationSection section, List<String> forbidden) {
        defs.clear();
        soulboundForbidden.clear();
        for (String m : forbidden) {
            Material material = Material.matchMaterial(m);
            if (material != null) soulboundForbidden.add(material);
        }
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            String vanilla = s.getString("vanilla");
            String custom = s.getString("custom");
            if ((vanilla == null) == (custom == null)) {
                logger.warning("Enchantment " + id + " needs exactly one of vanilla/custom");
                continue;
            }
            if (vanilla != null && ItemRenderer.enchantment(vanilla) == null) {
                logger.warning("Enchantment " + id + ": unknown vanilla enchantment " + vanilla);
                continue;
            }
            int level = s.getInt("level", 1);
            if (level < 1 || level > 255) {
                logger.warning("Enchantment " + id + ": bad level");
                continue;
            }
            Set<String> targets = new HashSet<>();
            for (String t : s.getStringList("targets")) targets.add(t.toLowerCase(Locale.ROOT));
            defs.put(id, new EnchantDef(id, vanilla, custom == null ? null : custom.toLowerCase(Locale.ROOT), level,
                    Set.copyOf(targets), s.getString("source", "library"), Set.copyOf(s.getStringList("incompatible")),
                    s.getBoolean("soulbound", false), s.getBoolean("over-others", false),
                    s.getInt("library.level", 0), cents(s.getDouble("library.set-price", 0)),
                    s.getInt("library.set-hammers", 0), cents(s.getDouble("library.apply-price", 0)),
                    s.getString("wonder.id"), cents(s.getDouble("wonder.price", 0))));
        }
    }

    private static long cents(double coins) {
        return Double.isFinite(coins) && coins > 0 ? Math.round(coins * 100) : 0;
    }

    public EnchantDef def(String id) {
        return defs.get(id);
    }

    public Collection<EnchantDef> all() {
        return Collections.unmodifiableCollection(defs.values());
    }

    /** Library enchantments available at the given library level (spec 04 §7.1). */
    public List<EnchantDef> library(int libraryLevel) {
        List<EnchantDef> out = new ArrayList<>();
        for (EnchantDef d : defs.values()) {
            if ("library".equals(d.source()) && d.libraryLevel() > 0 && d.libraryLevel() <= libraryLevel) out.add(d);
        }
        return out;
    }

    /** Enchantments sold by a wonder (spec 04 §7.2). */
    public List<EnchantDef> wonder(String wonderId) {
        List<EnchantDef> out = new ArrayList<>();
        for (EnchantDef d : defs.values()) {
            if ("wonder".equals(d.source()) && wonderId.equals(d.wonder())) out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ target classes

    /** Whether the stack belongs to the target class used in the catalog. */
    public boolean matches(ItemStack stack, String target) {
        ItemDef def = registry.def(stack);
        Material m = stack.getType();
        String name = m.name();
        return switch (target) {
            case "any" -> true;
            case "sword" -> def != null ? def.kind() == ItemKind.WEAPON : name.endsWith("_SWORD");
            case "bow" -> def != null ? def.kind() == ItemKind.BOW : m == Material.BOW || m == Material.CROSSBOW;
            case "weapon" -> matches(stack, "sword") || matches(stack, "bow");
            case "pickaxe" -> (def == null || def.kind() == ItemKind.TOOL) && name.endsWith("_PICKAXE");
            case "shovel" -> (def == null || def.kind() == ItemKind.TOOL) && name.endsWith("_SHOVEL");
            case "axe" -> (def == null || def.kind() == ItemKind.TOOL) && name.endsWith("_AXE");
            case "hoe" -> (def == null || def.kind() == ItemKind.TOOL) && name.endsWith("_HOE");
            case "tool" -> matches(stack, "pickaxe") || matches(stack, "shovel") || matches(stack, "axe")
                    || matches(stack, "hoe");
            case "fishing_rod" -> def == null && m == Material.FISHING_ROD;
            case "helmet" -> armorSlot(stack, def) == EquipmentSlot.HEAD;
            case "chestplate" -> armorSlot(stack, def) == EquipmentSlot.CHEST;
            case "leggings" -> armorSlot(stack, def) == EquipmentSlot.LEGS;
            case "boots" -> armorSlot(stack, def) == EquipmentSlot.FEET;
            case "armor" -> armorSlot(stack, def) != null;
            case "heavy_boots" -> def != null && def.isGear() && def.gear().armorClass() == ArmorClass.HEAVY
                    && def.gear().slot() == EquipmentSlot.FEET;
            default -> false;
        };
    }

    private static EquipmentSlot armorSlot(ItemStack stack, ItemDef def) {
        if (def != null) return def.kind() == ItemKind.ARMOR ? def.gear().slot() : null;
        String name = stack.getType().name();
        if (name.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (name.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (name.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (name.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return null;
    }

    // ------------------------------------------------------------------------------------ state

    /** Level of an effect on the stack: vanilla key ("minecraft:efficiency") or custom effect id. */
    public int level(ItemStack stack, String effectKey) {
        if (ItemData.empty(stack)) return 0;
        if (effectKey.contains(":")) {
            Enchantment e = ItemRenderer.enchantment(effectKey);
            return e == null ? 0 : stack.getEnchantmentLevel(e);
        }
        if (SOULBOUND.equals(effectKey)) return ItemData.soulbound(stack) ? 1 : 0;
        return ItemData.customEnchant(stack, effectKey);
    }

    private Set<String> effects(ItemStack stack) {
        Set<String> effects = new HashSet<>(ItemData.customEnchants(stack).keySet());
        stack.getEnchantments().keySet().forEach(e -> effects.add(e.getKey().toString()));
        if (ItemData.soulbound(stack)) effects.add(SOULBOUND);
        return effects;
    }

    private Set<String> incompatibleEffects(EnchantDef def) {
        Set<String> out = new HashSet<>();
        for (String id : def.incompatible()) {
            EnchantDef other = defs.get(id);
            out.add(other != null ? other.effectKey() : id);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ rules

    /** Throws a player-facing error when the enchantment cannot go on the item. */
    public void check(ItemStack stack, String enchantId) throws CivException {
        EnchantDef def = defs.get(enchantId);
        CivException.check(def != null, "items.enchant.unknown");
        CivException.check(!ItemData.empty(stack) && stack.getAmount() == 1, "items.enchant.no-item");
        ItemDef item = registry.def(stack);
        CivException.check(item == null || (!item.has(ItemFlag.NO_ENCHANT) && (item.isGear() || "any".equals(first(def)))),
                "items.enchant.not-allowed");
        boolean fits = false;
        for (String target : def.targets()) fits |= matches(stack, target);
        CivException.check(fits, "items.enchant.wrong-target");
        if (SOULBOUND.equals(def.customEffect()) || def.soulbound()) {
            CivException.check(item != null || !soulboundForbidden.contains(stack.getType()),
                    "items.enchant.soulbound-forbidden");
        }
        Set<String> current = effects(stack);
        Set<String> excluded = incompatibleEffects(def);
        for (String e : current) CivException.check(!excluded.contains(e), "items.enchant.incompatible");
        for (EnchantDef other : defs.values()) {
            if (current.contains(other.effectKey()) && incompatibleEffects(other).contains(def.effectKey())) {
                throw new CivException("items.enchant.incompatible");
            }
        }
        CivException.check(level(stack, def.effectKey()) < def.level(), "items.enchant.already");
    }

    private static String first(EnchantDef def) {
        return def.targets().size() == 1 ? def.targets().iterator().next() : "";
    }

    /** Checks and applies a catalog enchantment. The stack must be a live inventory stack. */
    public void apply(ItemStack stack, String enchantId) throws CivException {
        check(stack, enchantId);
        applyUnchecked(stack, defs.get(enchantId));
    }

    private void applyUnchecked(ItemStack stack, EnchantDef def) {
        if (def.isVanilla()) {
            Enchantment enchantment = ItemRenderer.enchantment(def.vanillaKey());
            if (enchantment != null) stack.addUnsafeEnchantment(enchantment, def.level());
        } else if (SOULBOUND.equals(def.customEffect())) {
            ItemData.soulbound(stack, true);
        } else {
            ItemData.customEnchant(stack, def.customEffect(), def.level());
            if (SKY_PUNISHMENT.equals(def.customEffect())) {
                // The world boss code (PvE module) reads this key for the +1 damage per level.
                stack.editPersistentDataContainer(pdc -> pdc.set(ItemKeys.HEAVENLY_PUNISHMENT,
                        PersistentDataType.INTEGER, def.level()));
            }
        }
        if (def.soulbound()) ItemData.soulbound(stack, true);
        rerender(stack);
    }

    /** Re-renders lore after a change (custom and vanilla stacks). */
    public void rerender(ItemStack stack) {
        if (registry.def(stack) != null) renderer.render(stack);
        else renderer.renderVanilla(stack);
    }
}
