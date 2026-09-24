package com.civcraft.mob;

import com.civcraft.pve.ItemSpec;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent;

/** Typed view of {@code balance/mobs.yml}. Rebuilt on load; read-only afterwards. */
public final class MobConfig {

    public record PoolEntry(MobType type, MobTier tier) {
    }

    private final Map<MobType, Map<MobTier, MobDef>> defs = new EnumMap<>(MobType.class);
    /** Biome key (minecraft:plains) → every mob that may spawn there. Several entries per biome are kept. */
    private final Map<String, List<PoolEntry>> biomes = new HashMap<>();
    final List<ItemSpec> commonDrops;
    final List<ItemSpec> lesserDrops;
    final Map<Material, Integer> weaponTiers = new EnumMap<>(Material.class);
    final int defaultWeaponTier;
    final boolean weaponTierRule;
    final boolean requirePlayerKill;
    final double minDamageAfterDefence;
    final Set<EntityDamageEvent.DamageCause> immune = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
    final boolean noSunBurn;

    // Yobo
    final int angryCount;
    final long waveCooldownMillis;
    final int maxWaves;
    final int angryIdleSeconds;
    // Savage
    final double slowChance;
    final int slowAmplifier;
    final double[] slowSeconds;
    // Ruffian
    final double potionBonusPerTier;
    final double splashRadius;
    // Behemoth
    final double[] slamIntervalSeconds;
    final double slamRadius;
    final double strongChance;
    final double strongMultiplier;
    final double knockback;
    final int aggroSeconds;

    // Spawning
    final Set<String> spawnWorlds = new HashSet<>();
    final boolean spawnEnabled;
    final long spawnIntervalTicks;
    final int spawnPlayersPerRun;
    final int radiusMin;
    final int radiusMax;
    final int playerRadius;
    final int playerCap;
    final int localRadius;
    final int localCap;
    final int groupMin;
    final int groupMax;
    final int attempts;
    final int spawnSafeRadius;
    final double naturalReplaceChance;
    final boolean cancelVanillaMonsters;
    final int campRadius;

    // Clear lag
    final boolean clearEnabled;
    final int clearIntervalMinutes;
    final List<Integer> clearWarnings = new ArrayList<>();
    final boolean clearItems;
    final boolean clearVanillaMonsters;

    MobConfig(YamlConfiguration c, String mainWorld, Logger log) {
        ConfigurationSection types = c.getConfigurationSection("entity-types");
        for (MobType type : MobType.values()) {
            EntityType entity = entityType(types == null ? null : types.getString(type.id()), log, type);
            ConfigurationSection typeSec = c.getConfigurationSection("mobs." + type.id());
            Map<MobTier, MobDef> byTier = new EnumMap<>(MobTier.class);
            for (MobTier tier : MobTier.values()) {
                ConfigurationSection s = typeSec == null ? null : typeSec.getConfigurationSection(tier.id());
                if (s == null) continue;
                ConfigurationSection d = typeSec.getConfigurationSection("defaults");
                List<?> coins = s.getList("coins", d == null ? List.of() : d.getList("coins", List.of()));
                long cMin = coins.size() > 0 && coins.get(0) instanceof Number n ? n.longValue() : 0;
                long cMax = coins.size() > 1 && coins.get(1) instanceof Number n ? n.longValue() : cMin;
                Set<Material> keep = new HashSet<>();
                for (String m : s.getStringList("keep-vanilla-drops").isEmpty() && d != null
                        ? d.getStringList("keep-vanilla-drops") : s.getStringList("keep-vanilla-drops")) {
                    Material mat = Material.matchMaterial(m);
                    if (mat != null) keep.add(mat);
                }
                List<?> drops = c.getList("drops." + type.id() + "." + tier.id(), List.of());
                byTier.put(tier, new MobDef(type, tier, entity,
                        positive(s, d, "hp", 20), positive(s, d, "damage", 2), positive(s, d, "speed", 1),
                        positive(s, d, "armor", 0), positive(s, d, "defence", 0), positive(s, d, "scale", 1),
                        positive(s, d, "follow-range", 32), positive(s, d, "knockback-resistance", 0),
                        Math.max(0, cMin), Math.max(Math.max(0, cMin), cMax), (int) positive(s, d, "xp", 5),
                        s.getBoolean("ignore-armor", d != null && d.getBoolean("ignore-armor", false)),
                        ItemSpec.parseList(drops, log, "mobs.yml drops." + type.id() + "." + tier.id()), keep));
            }
            defs.put(type, byTier);
        }
        commonDrops = ItemSpec.parseList(c.getList("common-drops"), log, "mobs.yml common-drops");
        lesserDrops = ItemSpec.parseList(c.getList("lesser-drops"), log, "mobs.yml lesser-drops");

        ConfigurationSection biomeSec = c.getConfigurationSection("biomes");
        if (biomeSec != null) {
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            for (String key : biomeSec.getKeys(false)) {
                String full = key.contains(":") ? key : "minecraft:" + key;
                if (registry.get(Key.key(full.toLowerCase(Locale.ROOT))) == null) {
                    log.warning("mobs.yml: unknown biome '" + key + "'");
                    continue;
                }
                List<PoolEntry> pool = biomes.computeIfAbsent(full.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
                for (String entry : biomeSec.getStringList(key)) {
                    String[] p = entry.split(":");
                    MobType type = p.length == 2 ? MobType.parse(p[0]) : null;
                    MobTier tier = p.length == 2 ? MobTier.parse(p[1]) : null;
                    if (type == null || tier == null || def(type, tier) == null) {
                        log.warning("mobs.yml: bad biome entry '" + entry + "' for " + key);
                        continue;
                    }
                    pool.add(new PoolEntry(type, tier));
                }
            }
        }

        ConfigurationSection wt = c.getConfigurationSection("weapon-tiers.materials");
        if (wt != null) {
            for (String k : wt.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null) weaponTiers.put(m, wt.getInt(k));
            }
        }
        defaultWeaponTier = c.getInt("weapon-tiers.default", 0);
        weaponTierRule = c.getBoolean("weapon-tiers.enabled", true);
        requirePlayerKill = c.getBoolean("rules.require-player-kill", true);
        minDamageAfterDefence = Math.max(0, c.getDouble("rules.min-damage-after-defence", 1.0));
        for (String cause : c.getStringList("rules.immune-damage")) {
            try {
                immune.add(EntityDamageEvent.DamageCause.valueOf(cause.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warning("mobs.yml: unknown damage cause " + cause);
            }
        }
        noSunBurn = c.getBoolean("rules.no-sun-burn", true);

        angryCount = Math.max(0, c.getInt("abilities.yobo.angry-count", 6));
        waveCooldownMillis = 1000L * Math.max(0, c.getInt("abilities.yobo.wave-cooldown-seconds", 30));
        maxWaves = Math.max(0, c.getInt("abilities.yobo.max-waves", 2));
        angryIdleSeconds = Math.max(1, c.getInt("abilities.yobo.angry-idle-despawn-seconds", 10));
        slowChance = c.getDouble("abilities.savage.slow-chance", 25);
        slowAmplifier = Math.max(0, c.getInt("abilities.savage.slow-level", 2) - 1);
        slowSeconds = perTier(c, "abilities.savage.slow-seconds", new double[]{3, 3.5, 4, 5});
        potionBonusPerTier = c.getDouble("abilities.ruffian.potion-bonus-per-tier", 0.125);
        splashRadius = c.getDouble("abilities.ruffian.splash-radius", 9);
        slamIntervalSeconds = perTier(c, "abilities.behemoth.interval-seconds", new double[]{6, 5.5, 5, 4.5});
        slamRadius = c.getDouble("abilities.behemoth.radius", 5.75);
        strongChance = c.getDouble("abilities.behemoth.strong-chance", 30);
        strongMultiplier = c.getDouble("abilities.behemoth.strong-multiplier", 1.5);
        knockback = c.getDouble("abilities.behemoth.knockback", 1.315);
        aggroSeconds = Math.max(1, c.getInt("abilities.behemoth.aggro-seconds", 30));

        List<String> worlds = c.getStringList("spawn.worlds");
        if (worlds.isEmpty()) spawnWorlds.add(mainWorld);
        else spawnWorlds.addAll(worlds);
        spawnEnabled = c.getBoolean("spawn.enabled", true);
        spawnIntervalTicks = Math.max(10, c.getLong("spawn.interval-ticks", 40));
        spawnPlayersPerRun = Math.max(1, c.getInt("spawn.players-per-run", 4));
        radiusMin = Math.max(8, c.getInt("spawn.radius-min", 24));
        radiusMax = Math.max(radiusMin + 1, c.getInt("spawn.radius-max", 64));
        playerRadius = Math.max(16, c.getInt("spawn.player-radius", 64));
        playerCap = Math.max(0, c.getInt("spawn.player-cap", 6));
        localRadius = Math.max(4, c.getInt("spawn.local-radius", 16));
        localCap = Math.max(1, c.getInt("spawn.local-cap", 4));
        groupMin = Math.max(1, c.getInt("spawn.group-min", 2));
        groupMax = Math.max(groupMin, c.getInt("spawn.group-max", 3));
        attempts = Math.max(1, c.getInt("spawn.attempts", 5));
        spawnSafeRadius = Math.max(0, c.getInt("spawn.safe-radius-from-world-spawn", 150));
        naturalReplaceChance = Math.clamp(c.getDouble("spawn.natural.replace-chance", 0.5), 0, 1);
        cancelVanillaMonsters = c.getBoolean("spawn.natural.cancel-unreplaced", false);
        campRadius = Math.max(0, c.getInt("spawn.camp-radius", 24));

        clearEnabled = c.getBoolean("clearlag.enabled", true);
        clearIntervalMinutes = Math.max(1, c.getInt("clearlag.interval-minutes", 20));
        for (Object o : c.getList("clearlag.warnings-seconds", List.of(60, 30, 10))) {
            if (o instanceof Number n && n.intValue() > 0) clearWarnings.add(n.intValue());
        }
        clearItems = c.getBoolean("clearlag.remove-items", true);
        clearVanillaMonsters = c.getBoolean("clearlag.remove-vanilla-monsters", true);
    }

    private static double positive(ConfigurationSection s, ConfigurationSection d, String key, double def) {
        double v = s.getDouble(key, d == null ? def : d.getDouble(key, def));
        return Double.isFinite(v) && v >= 0 ? v : def;
    }

    private static double[] perTier(YamlConfiguration c, String path, double[] def) {
        List<Double> list = c.getDoubleList(path);
        if (list.size() != 4) return def;
        return new double[]{list.get(0), list.get(1), list.get(2), list.get(3)};
    }

    private static EntityType entityType(String name, Logger log, MobType type) {
        EntityType fallback = switch (type) {
            case YOBO, ANGRY_YOBO -> EntityType.ZOMBIE;
            case SAVAGE -> EntityType.HUSK;
            case RUFFIAN -> EntityType.WITCH;
            case BEHEMOTH -> EntityType.IRON_GOLEM;
            case RAT -> EntityType.SILVERFISH;
        };
        if (name == null) return fallback;
        EntityType t = org.bukkit.Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (t == null || !t.isAlive() || !t.isSpawnable()) {
            log.warning("mobs.yml: bad entity type '" + name + "' for " + type.id() + ", using " + fallback);
            return fallback;
        }
        return t;
    }

    public MobDef def(MobType type, MobTier tier) {
        Map<MobTier, MobDef> m = defs.get(type);
        return m == null ? null : m.get(tier);
    }

    public List<PoolEntry> pool(Biome biome) {
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).getKey(biome);
        return key == null ? List.of() : biomes.getOrDefault(key.asString(), List.of());
    }

    public Map<String, List<PoolEntry>> biomes() {
        return biomes;
    }
}
