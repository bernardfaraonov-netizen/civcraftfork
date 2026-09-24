package com.civcraft.item.recipe;

import com.civcraft.item.def.ItemDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses {@code recipes:} and {@code compression:} sections. Pure logic apart from material lookups;
 * errors are collected and the offending recipe is skipped.
 *
 * <p>Ingredient tokens: {@code <custom id>}, {@code minecraft:<item>} or {@code #<group>}, each optionally
 * followed by {@code " xN"}.
 */
public final class RecipeParser {

    public static final Pattern RECIPE_ID = Pattern.compile("[a-z0-9_]{1,96}");
    private static final Pattern TOKEN = Pattern.compile("^\\s*(\\S+)(?:\\s+x(\\d+))?\\s*$");
    public static final int GRID = 9;

    private final Map<String, List<Material>> groups;
    private final Function<String, ItemDef> items;
    private final Map<String, String> techs;
    private final List<String> errors = new ArrayList<>();

    /**
     * @param groups vanilla material groups for "#name" ingredients
     * @param items  custom item lookup (null for unknown ids)
     * @param techs  symbolic tech name → science tech id
     */
    public RecipeParser(Map<String, List<Material>> groups, Function<String, ItemDef> items, Map<String, String> techs) {
        this.groups = groups;
        this.items = items;
        this.techs = techs;
    }

    public List<String> errors() {
        return errors;
    }

    // ------------------------------------------------------------------------------------ recipes

    public List<RecipeDef> parseRecipes(String file, ConfigurationSection section) {
        List<RecipeDef> result = new ArrayList<>();
        if (section == null) return result;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                errors.add(file + ": recipe " + id + " is not a section");
                continue;
            }
            try {
                result.add(parseRecipe(id, s));
            } catch (IllegalArgumentException e) {
                errors.add(file + ": recipe " + id + ": " + e.getMessage());
            }
        }
        return result;
    }

    public RecipeDef parseRecipe(String id, ConfigurationSection s) {
        if (!RECIPE_ID.matcher(id).matches()) throw new IllegalArgumentException("invalid recipe id");
        String resultToken = s.getString("result", id);
        String resultId = null;
        Material resultVanilla = null;
        ItemDef resultDef = null;
        if (resultToken.contains(":")) {
            resultVanilla = Material.matchMaterial(resultToken);
            if (resultVanilla == null || !resultVanilla.isItem()) {
                throw new IllegalArgumentException("unknown result " + resultToken);
            }
        } else {
            resultDef = items.apply(resultToken);
            if (resultDef == null) throw new IllegalArgumentException("unknown result item " + resultToken);
            resultId = resultToken;
        }
        int maxStack = resultDef != null ? resultDef.stack() : resultVanilla.getMaxStackSize();
        int amount = s.getInt("amount", 1);
        if (amount < 1 || amount > maxStack) throw new IllegalArgumentException("amount must be 1.." + maxStack);

        RecipeDef.Station station = RecipeDef.Station.valueOf(
                s.getString("station", "workbench").toUpperCase(Locale.ROOT));
        List<String> shape = List.of();
        Map<Character, Ingredient> keys = Map.of();
        List<Ingredient.Counted> ingredients = List.of();
        if (s.contains("shape")) {
            if (station != RecipeDef.Station.WORKBENCH) throw new IllegalArgumentException("only workbench recipes are shaped");
            shape = List.copyOf(s.getStringList("shape"));
            keys = parseKeys(shape, s.getConfigurationSection("keys"));
        } else {
            String listKey = s.contains("shapeless") ? "shapeless" : "ingredients";
            ingredients = parseList(s.getStringList(listKey));
            if (ingredients.isEmpty()) throw new IllegalArgumentException("no ingredients");
            int total = ingredients.stream().mapToInt(Ingredient.Counted::count).sum();
            if (station == RecipeDef.Station.WORKBENCH && total > GRID) {
                throw new IllegalArgumentException("a workbench recipe takes at most 9 items, got " + total);
            }
        }
        String tech = s.getString("tech");
        if (tech != null) tech = techs.getOrDefault(tech, tech);
        else if (resultDef != null) tech = resultDef.tech();
        String category = s.getString("category",
                resultDef != null ? resultDef.category() : "special").toLowerCase(Locale.ROOT);
        int tier = resultDef != null ? resultDef.tier() : 0;
        return new RecipeDef(id, resultId, resultVanilla, amount, station, shape, keys, ingredients, tech,
                s.getBoolean("tech-optional", false), category, tier);
    }

    private Map<Character, Ingredient> parseKeys(List<String> shape, ConfigurationSection keySection) {
        if (shape.isEmpty() || shape.size() > 3) throw new IllegalArgumentException("shape needs 1..3 rows");
        int width = shape.getFirst().length();
        if (width < 1 || width > 3) throw new IllegalArgumentException("shape rows need 1..3 columns");
        Set<Character> used = new HashSet<>();
        for (String row : shape) {
            if (row.length() != width) throw new IllegalArgumentException("shape rows differ in length");
            for (char c : row.toCharArray()) if (c != ' ') used.add(c);
        }
        if (used.isEmpty()) throw new IllegalArgumentException("empty shape");
        if (keySection == null) throw new IllegalArgumentException("missing keys");
        Map<Character, Ingredient> keys = new LinkedHashMap<>();
        for (String k : keySection.getKeys(false)) {
            if (k.length() != 1 || k.charAt(0) == ' ') throw new IllegalArgumentException("key must be one character: " + k);
            Ingredient.Counted counted = parseToken(keySection.getString(k, ""));
            if (counted.count() != 1) throw new IllegalArgumentException("shape keys take no count: " + k);
            keys.put(k.charAt(0), counted.ingredient());
        }
        for (char c : used) {
            if (!keys.containsKey(c)) throw new IllegalArgumentException("shape letter '" + c + "' has no key");
        }
        if (!used.containsAll(keys.keySet())) throw new IllegalArgumentException("unused keys " + keys.keySet());
        return keys;
    }

    List<Ingredient.Counted> parseList(List<String> tokens) {
        List<Ingredient.Counted> list = new ArrayList<>();
        for (String token : tokens) list.add(parseToken(token));
        return list;
    }

    /** Parses one ingredient token like {@code steel_ingot x2}, {@code minecraft:coal_block} or {@code #leaves x8}. */
    public Ingredient.Counted parseToken(String token) {
        Matcher m = TOKEN.matcher(token == null ? "" : token);
        if (!m.matches()) throw new IllegalArgumentException("bad ingredient '" + token + "'");
        String ref = m.group(1).toLowerCase(Locale.ROOT);
        int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        if (count < 1 || count > 100_000) throw new IllegalArgumentException("bad count in '" + token + "'");
        return new Ingredient.Counted(parseRef(ref), count);
    }

    private Ingredient parseRef(String ref) {
        if (ref.startsWith("#")) {
            String name = ref.substring(1);
            List<Material> group = groups.get(name);
            if (group == null || group.isEmpty()) throw new IllegalArgumentException("unknown group " + ref);
            return new Ingredient.Vanilla(group, name);
        }
        if (ref.contains(":")) {
            Material material = Material.matchMaterial(ref);
            if (material == null || !material.isItem() || material.isAir()) {
                throw new IllegalArgumentException("unknown material " + ref);
            }
            return new Ingredient.Vanilla(List.of(material), null);
        }
        if (items.apply(ref) == null) throw new IllegalArgumentException("unknown item " + ref);
        return new Ingredient.Custom(ref);
    }

    // ------------------------------------------------------------------------------------ compression

    /**
     * Expands compression chains: each entry of {@code inputs} (vanilla) makes one recipe
     * {@code ratio × input → chain[0]}, then {@code ratio × chain[k] → chain[k+1]}. With
     * {@code reversible: true} the reverse {@code chain[k+1] → ratio × chain[k]} recipes are added too.
     */
    public List<RecipeDef> parseCompression(String file, ConfigurationSection section) {
        List<RecipeDef> result = new ArrayList<>();
        if (section == null) return result;
        for (String name : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(name);
            try {
                if (s == null) throw new IllegalArgumentException("not a section");
                result.addAll(compression(name, s.getInt("ratio", 9), s.getStringList("inputs"),
                        s.getStringList("chain"), s.getBoolean("reversible", false), s.getString("tech")));
            } catch (IllegalArgumentException e) {
                errors.add(file + ": compression " + name + ": " + e.getMessage());
            }
        }
        return result;
    }

    public List<RecipeDef> compression(String name, int ratio, List<String> inputs, List<String> chain,
                                       boolean reversible, String techName) {
        if (!RECIPE_ID.matcher(name).matches()) throw new IllegalArgumentException("invalid name");
        if (ratio < 1 || ratio > GRID) throw new IllegalArgumentException("ratio must be 1..9");
        if (chain.isEmpty()) throw new IllegalArgumentException("empty chain");
        if (inputs.isEmpty() && chain.size() < 2) throw new IllegalArgumentException("nothing to compress");
        String tech = techName == null ? null : techs.getOrDefault(techName, techName);
        List<ItemDef> defs = new ArrayList<>();
        for (String id : chain) {
            ItemDef def = items.apply(id);
            if (def == null) throw new IllegalArgumentException("unknown item " + id);
            defs.add(def);
        }
        List<RecipeDef> out = new ArrayList<>();
        ItemDef first = defs.getFirst();
        for (String input : inputs) {
            Ingredient.Counted in = parseToken(input);
            if (in.count() != 1) throw new IllegalArgumentException("inputs take no count: " + input);
            String suffix = in.ingredient() instanceof Ingredient.Vanilla v && v.group() != null
                    ? v.group() : in.ingredient().token().replace("minecraft:", "").replace(':', '_');
            String id = name + "_0_" + suffix;
            out.add(new RecipeDef(id, first.id(), null, 1, RecipeDef.Station.WORKBENCH, List.of(), Map.of(),
                    List.of(new Ingredient.Counted(in.ingredient(), ratio)), tech, false, first.category(), first.tier()));
        }
        for (int k = 0; k + 1 < defs.size(); k++) {
            ItemDef from = defs.get(k);
            ItemDef to = defs.get(k + 1);
            out.add(new RecipeDef(name + "_" + (k + 1), to.id(), null, 1, RecipeDef.Station.WORKBENCH, List.of(),
                    Map.of(), List.of(new Ingredient.Counted(new Ingredient.Custom(from.id()), ratio)), tech, false,
                    to.category(), to.tier()));
            if (reversible) {
                if (ratio > from.stack()) throw new IllegalArgumentException("reverse amount exceeds stack of " + from.id());
                out.add(new RecipeDef(name + "_" + (k + 1) + "_back", from.id(), null, ratio, RecipeDef.Station.WORKBENCH,
                        List.of(), Map.of(), List.of(new Ingredient.Counted(new Ingredient.Custom(to.id()), 1)), tech,
                        false, from.category(), from.tier()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ groups

    /** Parses a {@code groups:} section (name → list of materials) into {@code into}. */
    public static void parseGroups(ConfigurationSection section, Map<String, List<Material>> into, List<String> errors) {
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            List<Material> materials = new ArrayList<>();
            for (String m : section.getStringList(name)) {
                Material material = Material.matchMaterial(m);
                if (material == null || !material.isItem()) {
                    errors.add("group " + name + ": unknown material " + m);
                    continue;
                }
                materials.add(material);
            }
            if (!materials.isEmpty()) into.put(name.toLowerCase(Locale.ROOT), List.copyOf(materials));
        }
    }
}
