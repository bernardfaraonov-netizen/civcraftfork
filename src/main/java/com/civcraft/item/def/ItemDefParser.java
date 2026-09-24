package com.civcraft.item.def;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Parses the {@code items:} section of item balance files into {@link ItemDef}s. Invalid entries are
 * skipped with a message in {@link #errors()} so one typo does not disable the whole module.
 */
public final class ItemDefParser {

    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");

    /**
     * Global defaults the parser needs.
     *
     * @param techs             symbolic tech name → science tech id
     * @param tierRarity        default rarity per tier
     * @param defaultDurability default max durability per tier
     * @param deathLoss         default share of durability lost per death
     */
    public record Context(Map<String, String> techs, Map<Integer, String> tierRarity,
                          Map<Integer, Integer> defaultDurability, double deathLoss) {
    }

    private final Context ctx;
    private final List<String> errors = new ArrayList<>();

    public ItemDefParser(Context ctx) {
        this.ctx = ctx;
    }

    public List<String> errors() {
        return errors;
    }

    public List<ItemDef> parse(String file, ConfigurationSection items) {
        List<ItemDef> result = new ArrayList<>();
        if (items == null) return result;
        for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            if (s == null) {
                errors.add(file + ": item " + id + " is not a section");
                continue;
            }
            try {
                result.add(parseOne(id, s));
            } catch (IllegalArgumentException e) {
                errors.add(file + ": item " + id + ": " + e.getMessage());
            }
        }
        return result;
    }

    ItemDef parseOne(String id, ConfigurationSection s) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("invalid id (use a-z, 0-9, _)");
        String name = s.getString("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("missing name");
        Material material = material(s.getString("material"));
        String model = s.getString("model");
        if (model != null && NamespacedKey.fromString(model) == null) {
            throw new IllegalArgumentException("invalid model key " + model);
        }
        Float modelData = s.contains("model-data") ? (float) s.getDouble("model-data") : null;
        int tier = s.getInt("tier", 0);
        if (tier < 0 || tier > 4) throw new IllegalArgumentException("tier must be 0..4");
        ItemKind kind = ItemKind.parse(s.getString("kind", "material"));
        String category = s.getString("category", kind.defaultCategory()).toLowerCase(Locale.ROOT);
        String rarity = s.getString("rarity",
                kind == ItemKind.ARTIFACT ? "artifact" : ctx.tierRarity().getOrDefault(tier, "common"));

        Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        for (String f : s.getStringList("flags")) {
            try {
                flags.add(ItemFlag.parse(f));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown flag " + f);
            }
        }
        if (kind == ItemKind.ARTIFACT) flags.add(ItemFlag.KEEP_ON_DEATH);

        Map<String, Integer> enchants = intMap(s.getConfigurationSection("enchants"), true);
        Map<String, Integer> customEnchants = intMap(s.getConfigurationSection("custom-enchants"), false);
        int color = color(s.getString("color"));
        String tech = s.getString("tech");
        if (tech != null) tech = ctx.techs().getOrDefault(tech, tech);

        GearStats gear = kind.isGear() ? gear(kind, tier, s, flags) : null;
        int defaultStack = gear != null || kind == ItemKind.UNIT || kind == ItemKind.ARTIFACT ? 1 : 64;
        int stack = s.getInt("stack", defaultStack);
        if (stack < 1 || stack > 99) throw new IllegalArgumentException("stack must be 1..99");
        if (gear != null && gear.durability() > 0) stack = 1;

        Map<String, String> data = new LinkedHashMap<>();
        ConfigurationSection ds = s.getConfigurationSection("data");
        if (ds != null) {
            for (String k : ds.getKeys(false)) data.put(k, String.valueOf(ds.get(k)));
        }
        return new ItemDef(id, name, material, model, modelData, tier, kind, category, rarity,
                s.getBoolean("glint", false), stack, List.copyOf(s.getStringList("lore")),
                Collections.unmodifiableSet(flags), enchants, customEnchants, color, tech, gear,
                Collections.unmodifiableMap(data), s.getBoolean("single-use", false));
    }

    private GearStats gear(ItemKind kind, int tier, ConfigurationSection s, Set<ItemFlag> flags) {
        double damage = s.getDouble("damage", 0);
        if ((kind == ItemKind.WEAPON || kind == ItemKind.BOW) && damage <= 0) {
            throw new IllegalArgumentException("weapons need damage > 0");
        }
        double noTech = s.contains("no-tech-damage") ? s.getDouble("no-tech-damage") : Double.NaN;
        ArmorClass armorClass = null;
        EquipmentSlot slot = null;
        if (kind == ItemKind.ARMOR) {
            armorClass = ArmorClass.parse(require(s, "armor-class"));
            slot = slot(require(s, "slot"));
        }
        double armor = s.getDouble("armor", 0);
        double health = s.getDouble("health", 0);
        double speed = s.getDouble("speed", 0);
        if (!Double.isFinite(damage) || !Double.isFinite(armor) || !Double.isFinite(health) || !Double.isFinite(speed)
                || damage < 0 || armor < 0 || health < 0 || Math.abs(speed) > 1) {
            throw new IllegalArgumentException("invalid combat numbers");
        }
        SharpenType defaultSharpen = switch (kind) {
            case WEAPON, BOW -> SharpenType.ATTACK;
            case ARMOR -> armorClass == ArmorClass.HEAVY || armorClass == ArmorClass.LIGHT
                    ? SharpenType.DEFENSE : SharpenType.NONE;
            default -> SharpenType.NONE;
        };
        SharpenType sharpen = s.contains("sharpen") ? SharpenType.parse(s.getString("sharpen")) : defaultSharpen;
        int defaultDurability = flags.contains(ItemFlag.VANILLA_DURABILITY) || flags.contains(ItemFlag.UNBREAKABLE)
                ? 0 : ctx.defaultDurability().getOrDefault(Math.max(1, tier), 500);
        int durability = s.getInt("durability", defaultDurability);
        if (durability < 0) throw new IllegalArgumentException("durability must be >= 0");
        double deathLoss = s.getDouble("death-loss", ctx.deathLoss());
        if (deathLoss < 0 || deathLoss > 1) throw new IllegalArgumentException("death-loss must be 0..1");
        int repairTier = s.getInt("repair-tier", tier);
        long repairCost = s.contains("repair-cost") ? Math.round(s.getDouble("repair-cost") * 100) : -1;
        Realm realm = Realm.parse(s.getString("realm", "normal"));
        Set<String> effects = new TreeSet<>();
        for (String e : s.getStringList("effects")) effects.add(e.toLowerCase(Locale.ROOT));
        return new GearStats(damage, noTech, armor, armorClass, slot, health, speed, sharpen, durability, deathLoss,
                repairTier, repairCost, realm, Collections.unmodifiableSet(effects));
    }

    private static String require(ConfigurationSection s, String key) {
        String value = s.getString(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    static Material material(String name) {
        if (name == null) throw new IllegalArgumentException("missing material");
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isItem() || m.isAir()) throw new IllegalArgumentException("unknown item material " + name);
        return m;
    }

    static EquipmentSlot slot(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("unknown slot " + value);
        };
    }

    static int color(String value) {
        if (value == null) return -1;
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            int rgb = Integer.parseInt(hex, 16);
            if (hex.length() != 6) throw new NumberFormatException();
            return rgb;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid color " + value);
        }
    }

    private static Map<String, Integer> intMap(ConfigurationSection s, boolean namespaced) {
        if (s == null) return Map.of();
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String key : s.getKeys(false)) {
            int level = s.getInt(key);
            if (level < 1 || level > 255) throw new IllegalArgumentException("enchant level must be 1..255: " + key);
            String k = namespaced && !key.contains(":") ? "minecraft:" + key : key;
            map.put(k.toLowerCase(Locale.ROOT), level);
        }
        return Collections.unmodifiableMap(map);
    }
}
