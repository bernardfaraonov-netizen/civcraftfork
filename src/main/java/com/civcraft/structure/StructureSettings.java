package com.civcraft.structure;

import com.civcraft.balance.Balance;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Typed view of the non-type sections of {@code balance/structures.yml}. */
record StructureSettings(
        double speedMultiplier,
        double fallbackHammers,
        boolean pauseWonderWhileBuilding,
        int blocksPerTick,
        int announceStep,
        double cancelRefund,
        double raceRefund,
        double architectsPer2Percent,
        double architectsMax,
        boolean upkeepUnderConstruction,
        int saveIntervalSeconds,
        double groundRatio,
        int minY,
        int waterYTolerance,
        double waterMinRatio,
        double confirmRadius,
        int confirmSeconds,
        double mainBuildingDistance,
        int wonderMinAgeDays,
        int wondersPerTown,
        List<String> wonderWorlds,
        double repairCostShare,
        double repairHammersDivisor,
        int refreshCooldownMinutes,
        Material rubble,
        double rubbleShare,
        int fireBlocks,
        Material controlBlock,
        Material controlBase,
        Material controlDestroyed,
        double controlHologramHeight,
        double bossbarRadius,
        boolean progressHologram,
        boolean previewEnabled,
        Material previewValid,
        Material previewInvalid,
        int beamHeight,
        float edgeThickness,
        String interactContainers,
        String interactDoors,
        String interactOther,
        Map<String, String> themes) {

    static StructureSettings load(Balance balance) {
        String f = "structures";
        double coreSpeed = balance.getDouble("core", "hammers.build-speed-multiplier", 2.0);
        Map<String, String> themes = new LinkedHashMap<>();
        ConfigurationSection t = balance.section(f, "themes");
        for (String key : t.getKeys(false)) themes.put(key.toLowerCase(Locale.ROOT), t.getString(key + ".name", key));
        if (themes.isEmpty()) themes.put("default", "default");
        return new StructureSettings(
                balance.getDouble(f, "construction.speed-multiplier", coreSpeed),
                balance.getDouble(f, "construction.fallback-hammers", 100),
                balance.file(f).getBoolean("construction.pause-wonder-while-building", false),
                Math.max(16, balance.getInt(f, "construction.blocks-per-tick", 300)),
                Math.max(1, balance.getInt(f, "construction.announce-step", 5)),
                clamp01(balance.getDouble(f, "construction.cancel-refund", 0.5)),
                clamp01(balance.getDouble(f, "construction.wonder-race-refund", 0.5)),
                Math.max(0, balance.getDouble(f, "construction.architects-per-2-percent", 0.01)),
                clamp01(balance.getDouble(f, "construction.architects-max", 0.5)),
                balance.file(f).getBoolean("construction.upkeep-under-construction", false),
                Math.max(5, balance.getInt(f, "construction.save-interval-seconds", 15)),
                clamp01(balance.getDouble(f, "validation.ground-solid-ratio", 0.8)),
                balance.getInt(f, "validation.min-y", 9),
                Math.max(0, balance.getInt(f, "validation.water-y-tolerance", 10)),
                clamp01(balance.getDouble(f, "validation.water-min-ratio", 0.5)),
                Math.max(1, balance.getDouble(f, "validation.confirm-radius", 12)),
                Math.max(10, balance.getInt(f, "validation.confirm-seconds", 90)),
                Math.max(0, balance.getDouble(f, "validation.main-building-distance", 150)),
                Math.max(0, balance.getInt(f, "validation.wonder-min-age-days", 3)),
                Math.max(0, balance.getInt(f, "validation.wonders-per-town", 2)),
                balance.file(f).getStringList("validation.wonder-worlds"),
                clamp01(balance.getDouble(f, "repair.cost-share", 0.5)),
                Math.max(1, balance.getDouble(f, "repair.hammers-divisor", 3)),
                Math.max(0, balance.getInt(f, "refresh.cooldown-minutes", 10)),
                material(balance.file(f).getString("destroy.rubble-block"), Material.COBBLESTONE),
                clamp01(balance.getDouble(f, "destroy.rubble-share", 0.6)),
                Math.max(0, balance.getInt(f, "destroy.fire-blocks", 16)),
                material(balance.file(f).getString("control.block"), Material.OBSIDIAN),
                material(balance.file(f).getString("control.base-block"), Material.CHISELED_STONE_BRICKS),
                material(balance.file(f).getString("control.destroyed-block"), Material.COBBLESTONE),
                balance.getDouble(f, "control.hologram-height", 1.6),
                Math.max(8, balance.getDouble(f, "progress.bossbar-radius", 96)),
                balance.file(f).getBoolean("progress.hologram", true),
                balance.file(f).getBoolean("preview.enabled", true),
                material(balance.file(f).getString("preview.valid-block"), Material.LIME_STAINED_GLASS),
                material(balance.file(f).getString("preview.invalid-block"), Material.RED_STAINED_GLASS),
                Math.max(1, balance.getInt(f, "preview.beam-height", 40)),
                (float) Math.max(0.02, balance.getDouble(f, "preview.edge-thickness", 0.08)),
                balance.file(f).getString("interact.containers", "town").toLowerCase(Locale.ROOT),
                balance.file(f).getString("interact.doors", "all").toLowerCase(Locale.ROOT),
                balance.file(f).getString("interact.other", "all").toLowerCase(Locale.ROOT),
                java.util.Collections.unmodifiableMap(themes));
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private static Material material(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m != null && m.isBlock() ? m : def;
    }

    boolean themeExists(String theme) {
        return theme != null && themes.containsKey(theme.toLowerCase(Locale.ROOT));
    }
}
