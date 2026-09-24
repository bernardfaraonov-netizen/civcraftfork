package com.civcraft.item;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryType;

/** Typed view of {@code balance/items.yml}: combat, armor, effects and guard settings. */
public final class ItemRules {

    // worlds
    public final Set<String> valleyWorlds;
    public final Set<String> dungeonWorlds;
    // combat
    public final boolean combatEnabled;
    public final boolean disableAttackCooldown;
    public final double critMultiplier;
    public final double armorPerPoint;
    public final double armorMaxReduction;
    public final double noTechMultiplier;
    public final double minDamage;
    public final double wrongRealmDamage;
    public final double sweepDamage;
    public final Map<Material, Double> vanillaWeapons;
    public final Map<Material, Double> vanillaBows;
    public final double toolDamage;
    public final int swordCapWithoutSet;
    public final int soulCap;
    public final int soulCapDungeon;
    public final double soulThreshold;
    public final double soulMultiplier;
    public final boolean soulHalvingFullSet;
    public final String weaponDamageStat;
    public final String playerDamageStat;
    public final String armorPerPieceStat;
    // armor
    public final double dungeonHeavySpeedFactor;
    public final double soulFullSetHealth;
    public final boolean soulRegeneration;
    public final double turtleArmor;
    // sharpening effects
    public final double attackPerLevel;
    public final double defenseHealthPerLevel;
    public final double defenseHealthCap;
    public final String highLevelTech;
    public final int unlockedCap;
    // durability
    public final Map<String, Double> artifactSaveChance;
    // soulbound
    public final boolean soulboundBlockDrop;
    public final boolean soulboundBlockContainers;
    // guards
    public final List<String> removedRecipes;
    public final Set<InventoryType> blockedStations;
    public final Set<Material> blockedBlocks;
    // effects
    public final double lightningChance;
    public final double lightningDamage;
    public final double criticalChance;
    public final double criticalDamage;
    public final double rootsChance;
    public final int rootsAmplifier;
    public final double rootsSeconds;
    public final double rootsTargetCooldown;
    public final double rootsNotifyCooldown;
    public final double punchoutChance;
    public final double waterWalkingSeconds;
    public final int waterWalkingRadius;
    public final String muteSetting;

    public ItemRules(YamlConfiguration c, Map<String, String> techs) {
        valleyWorlds = Set.copyOf(c.getStringList("worlds.valley"));
        dungeonWorlds = Set.copyOf(c.getStringList("worlds.dungeon"));

        combatEnabled = c.getBoolean("combat.enabled", true);
        disableAttackCooldown = c.getBoolean("combat.disable-attack-cooldown", true);
        critMultiplier = positive(c.getDouble("combat.crit-multiplier", 1.5), 1.5);
        armorPerPoint = positive(c.getDouble("combat.armor-per-point", 0.04), 0.04);
        armorMaxReduction = Math.min(0.95, positive(c.getDouble("combat.armor-max-reduction", 0.8), 0.8));
        noTechMultiplier = positive(c.getDouble("combat.no-tech-multiplier", 0.5), 0.5);
        minDamage = positive(c.getDouble("combat.min-damage", 0.5), 0.5);
        wrongRealmDamage = positive(c.getDouble("combat.wrong-realm-damage", 1.0), 1.0);
        sweepDamage = positive(c.getDouble("combat.sweep-damage", 1.0), 1.0);
        vanillaWeapons = materialDoubles(c.getConfigurationSection("combat.vanilla-weapons"));
        vanillaBows = materialDoubles(c.getConfigurationSection("combat.vanilla-bows"));
        toolDamage = positive(c.getDouble("combat.tool-damage", 2), 2);
        swordCapWithoutSet = c.getInt("combat.sword-sharpen-cap-without-set", 3);
        soulCap = c.getInt("combat.soul-sharpen-cap", 6);
        soulCapDungeon = c.getInt("combat.soul-sharpen-cap-dungeon", 5);
        soulThreshold = c.getDouble("combat.soul-damage-threshold", 3.5);
        soulMultiplier = positive(c.getDouble("combat.soul-damage-multiplier", 0.5), 0.5);
        soulHalvingFullSet = c.getBoolean("combat.soul-halving-requires-full-set", true);
        weaponDamageStat = c.getString("combat.weapon-damage-stat", "weapon_damage");
        playerDamageStat = c.getString("combat.player-damage-stat", "player_damage");
        armorPerPieceStat = c.getString("armor.armor-per-piece-stat", "armor_per_piece");

        dungeonHeavySpeedFactor = c.getDouble("armor.dungeon-heavy-speed-factor", 0.5);
        soulFullSetHealth = c.getDouble("armor.soul.full-set-health", 2.0);
        soulRegeneration = c.getBoolean("armor.soul.regeneration", true);
        turtleArmor = c.getDouble("armor.turtle-armor", 0.15);

        attackPerLevel = c.getDouble("sharpening.attack-per-level", 1.0);
        defenseHealthPerLevel = c.getDouble("sharpening.defense-health-per-level", 1.0);
        defenseHealthCap = c.getDouble("sharpening.defense-health-cap-per-piece", 5.0);
        String hlt = c.getString("sharpening.high-level-tech");
        highLevelTech = hlt == null ? null : techs.getOrDefault(hlt, hlt);
        unlockedCap = c.getInt("sharpening.unlocked-cap", 2);

        Map<String, Double> save = new HashMap<>();
        ConfigurationSection sc = c.getConfigurationSection("durability.artifact-save-chance");
        if (sc != null) for (String k : sc.getKeys(false)) save.put(k, clamp01(sc.getDouble(k)));
        artifactSaveChance = Map.copyOf(save);

        soulboundBlockDrop = c.getBoolean("soulbound.block-drop", false);
        soulboundBlockContainers = c.getBoolean("soulbound.block-containers", false);

        removedRecipes = List.copyOf(c.getStringList("vanilla.removed-recipes"));
        Set<InventoryType> stations = EnumSet.noneOf(InventoryType.class);
        for (String s : c.getStringList("vanilla.blocked-stations")) {
            try {
                stations.add(InventoryType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // unknown inventory type in this server version
            }
        }
        blockedStations = Set.copyOf(stations);
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("vanilla.blocked-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blocks.add(m);
        }
        blockedBlocks = Set.copyOf(blocks);

        lightningChance = clamp01(c.getDouble("effects.lightning.chance", 0.07));
        lightningDamage = c.getDouble("effects.lightning.damage", 5);
        criticalChance = clamp01(c.getDouble("effects.critical.chance", 0.15));
        criticalDamage = c.getDouble("effects.critical.damage", 3);
        rootsChance = clamp01(c.getDouble("effects.roots.chance", 0.15));
        rootsAmplifier = c.getInt("effects.roots.amplifier", 4);
        rootsSeconds = c.getDouble("effects.roots.seconds", 3);
        rootsTargetCooldown = c.getDouble("effects.roots.target-cooldown", 5.5);
        rootsNotifyCooldown = c.getDouble("effects.roots.notify-cooldown", 15);
        punchoutChance = clamp01(c.getDouble("effects.punchout.chance", 0.5));
        waterWalkingSeconds = c.getDouble("effects.water-walking.seconds", 3);
        waterWalkingRadius = Math.max(0, Math.min(3, c.getInt("effects.water-walking.radius", 1)));
        muteSetting = c.getString("effects.mute-setting", "mute-fire");
    }

    public boolean isValley(World world) {
        return world != null && valleyWorlds.contains(world.getName());
    }

    public boolean isDungeon(World world) {
        return world != null && dungeonWorlds.contains(world.getName());
    }

    private static double positive(double value, double def) {
        return Double.isFinite(value) && value > 0 ? value : def;
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static Map<Material, Double> materialDoubles(ConfigurationSection s) {
        Map<Material, Double> map = new EnumMap<>(Material.class);
        if (s == null) return map;
        for (String key : s.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            double v = s.getDouble(key);
            if (m != null && Double.isFinite(v) && v >= 0) map.put(m, v);
        }
        return map;
    }
}
