package com.civcraft.structure.type;

import com.civcraft.core.util.Money;
import com.civcraft.effect.EffectParser;
import com.civcraft.effect.Modifier;
import com.civcraft.effect.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable definition of a structure type loaded from {@code balance/structures.yml} / {@code wonders.yml}.
 * Money values are hundredths of a coin.
 */
public record StructureType(
        String id,
        String name,
        List<String> aliases,
        Category category,
        int era,
        List<String> techs,
        List<Requirement> requires,
        boolean requiresActive,
        String nation,
        String replaces,
        int chunksX,
        int chunksZ,
        int[] segment,
        long cost,
        long relocateCost,
        long upkeep,
        double hammers,
        int hp,
        int hpCap,
        double regen,
        boolean warRegen,
        int score,
        int limit,
        int civLimit,
        String limitGroup,
        boolean slot,
        ClaimRule claim,
        WaterRule water,
        Integer minY,
        Integer maxY,
        String spacingGroup,
        int spacing,
        String template,
        int yShift,
        String style,
        List<MarkerSpec> markers,
        List<String> tags,
        NavigableMap<Integer, LevelDef> levels,
        int maxLevel,
        int startLevel,
        boolean loseLevelOnDestroy,
        List<String> infrastructure,
        List<Modifier> effects,
        Material icon,
        boolean destroyable,
        boolean demolishable,
        ControlDef control,
        boolean capitalOnly,
        int cultureLevel,
        boolean warOnly,
        String requiredItem,
        String resourcePoint,
        boolean floating) {

    public boolean isWonder() {
        return category.isWonder();
    }

    public boolean isWorldWonder() {
        return category == Category.WONDER;
    }

    public boolean isMain() {
        return category == Category.MAIN;
    }

    /** Whether the type is built with /build from a template (not walls, roads or war-only structures). */
    public boolean usesTemplate() {
        return !category.isCustom();
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** Counts against the per-town wonder limit (spec 03 §4.3: world wonders only). */
    public boolean usesWonderSlot() {
        return category == Category.WONDER;
    }

    /** Group used for per-civ limits (war camps and war ships share one limit). */
    public String limitKey() {
        return limitGroup != null ? limitGroup : (replaces != null ? replaces : id);
    }

    /** Footprint size in blocks along the template axes. */
    public int blocksX() {
        return chunksX * 16;
    }

    public int blocksZ() {
        return chunksZ * 16;
    }

    public LevelDef level(int level) {
        return levels.get(level);
    }

    public int topLevel() {
        int top = levels.isEmpty() ? 1 : Math.max(1, levels.lastKey());
        return Math.max(top, maxLevel);
    }

    // --- loading --------------------------------------------------------------------------------------------------

    public static StructureType load(String id, ConfigurationSection s, Logger logger) {
        Category category = Category.parse(s.getString("category", "building"));
        List<Integer> size = s.getIntegerList("size");
        int cx = size.size() >= 1 ? Math.max(1, size.get(0)) : 1;
        int cz = size.size() >= 2 ? Math.max(1, size.get(1)) : cx;
        List<Integer> seg = s.getIntegerList("segment");
        int[] segment = seg.size() == 3 ? new int[]{seg.get(0), seg.get(1), seg.get(2)} : null;

        List<Requirement> requires = new ArrayList<>();
        for (Object o : s.getList("requires", List.of())) {
            if (o instanceof String type) {
                requires.add(new Requirement(type.trim(), Requirement.Scope.TOWN, 0));
            } else if (o instanceof Map<?, ?> map && map.get("type") != null) {
                Object scope = map.get("scope");
                Object level = map.get("level");
                requires.add(new Requirement(map.get("type").toString().trim(),
                        scope == null ? Requirement.Scope.TOWN : Requirement.Scope.parse(scope.toString()),
                        level instanceof Number n ? n.intValue() : 0));
            }
        }

        NavigableMap<Integer, LevelDef> levels = new TreeMap<>();
        ConfigurationSection lv = s.getConfigurationSection("levels");
        if (lv != null) {
            for (String key : lv.getKeys(false)) {
                ConfigurationSection l = lv.getConfigurationSection(key);
                if (l == null) continue;
                int n;
                try {
                    n = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    logger.warning("Structure " + id + ": bad level key " + key);
                    continue;
                }
                levels.put(n, new LevelDef(n, Money.ofCoins(Math.max(0, l.getDouble("cost"))),
                        Math.max(0, l.getDouble("hammers")), l.getString("tech")));
            }
        }

        List<MarkerSpec> markers = new ArrayList<>();
        for (String m : s.getStringList("markers")) markers.add(MarkerSpec.parse(m));

        ControlDef control = null;
        ConfigurationSection c = s.getConfigurationSection("control");
        if (c != null) {
            control = new ControlDef(Math.max(1, c.getInt("hp", 20)), c.getInt("per-era", 0), c.getInt("era-from", 0),
                    c.getInt("max", 0), c.getBoolean("inherit", false) || "main".equalsIgnoreCase(c.getString("inherit")),
                    c.getString("stat", category == Category.SPECIAL ? "warcamp_control_hp" : "control_hp"));
        }

        List<String> spacing = s.getStringList("spacing");
        String spacingGroup = spacing.size() == 2 ? spacing.get(0) : null;
        int spacingValue = 0;
        if (spacingGroup != null) {
            try {
                spacingValue = Integer.parseInt(spacing.get(1).trim());
            } catch (NumberFormatException e) {
                logger.warning("Structure " + id + ": bad spacing " + spacing);
                spacingGroup = null;
            }
        }

        Material icon = Material.matchMaterial(s.getString("icon", "BRICKS"));
        if (icon == null || !icon.isItem()) {
            logger.warning("Structure " + id + ": unknown icon " + s.getString("icon"));
            icon = Material.BRICKS;
        }

        List<Modifier> effects = EffectParser.parse(s.getList("effects"), Scope.TOWN, "structure:" + id);

        boolean wonder = category.isWonder();
        return new StructureType(
                id,
                s.getString("name", id),
                lower(s.getStringList("aliases")),
                category,
                Math.max(0, s.getInt("era", 0)),
                lower(s.getStringList("tech")),
                List.copyOf(requires),
                s.getBoolean("requires-active", category == Category.NATIONAL_WONDER),
                blankToNull(s.getString("nation")),
                blankToNull(s.getString("replaces")),
                cx, cz, segment,
                Money.ofCoins(Math.max(0, s.getDouble("cost"))),
                Money.ofCoins(Math.max(0, s.getDouble("relocate-cost", s.getDouble("cost")))),
                Money.ofCoins(Math.max(0, s.getDouble("upkeep"))),
                Math.max(0, s.getDouble("hammers")),
                Math.max(0, s.getInt("hp")),
                Math.max(0, s.getInt("hp-cap")),
                Math.max(0, s.getDouble("regen")),
                s.getBoolean("war-regen", !wonder || s.getDouble("regen") > 0),
                Math.max(0, s.getInt("score")),
                Math.max(0, s.getInt("limit", wonder ? 1 : 0)),
                Math.max(0, s.getInt("civ-limit")),
                blankToNull(s.getString("limit-group")),
                s.getBoolean("slot", false),
                ClaimRule.parse(s.getString("claim", wonder ? "auto" : "culture")),
                WaterRule.parse(s.getString("water", "none")),
                s.isInt("min-y") ? s.getInt("min-y") : null,
                s.isInt("max-y") ? s.getInt("max-y") : null,
                spacingGroup, spacingValue,
                s.getString("template", id),
                s.getInt("y-shift", 0),
                s.getString("style", wonder ? "wonder" : "hall").toLowerCase(Locale.ROOT),
                List.copyOf(markers),
                lower(s.getStringList("tags")),
                Collections.unmodifiableNavigableMap(levels),
                Math.max(1, s.getInt("max-level", 1)),
                Math.max(0, s.getInt("start-level", 1)),
                s.getBoolean("lose-level-on-destroy", false),
                lower(s.getStringList("infrastructure")),
                List.copyOf(effects),
                icon,
                s.getBoolean("destroyable", false),
                s.getBoolean("demolish", true),
                control,
                s.getBoolean("capital-only", false),
                Math.max(0, s.getInt("culture-level", 0)),
                s.getBoolean("war-only", false),
                blankToNull(s.getString("required-item")),
                blankToNull(s.getString("resource-point")),
                s.getBoolean("floating", false));
    }

    private static List<String> lower(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String v : values) result.add(v.trim().toLowerCase(Locale.ROOT));
        return List.copyOf(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
