package com.civcraft.item.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * A CivCraft recipe.
 *
 * @param id           recipe id (also the key {@code civcraft:<id>} of the Bukkit recipe)
 * @param resultId     custom result id, or null when the result is a vanilla item
 * @param resultVanilla vanilla result material, or null for a custom result
 * @param amount       result amount
 * @param station      where it is crafted
 * @param shape        rows of a shaped recipe (empty for shapeless/factory)
 * @param keys         shape letters → ingredient
 * @param ingredients  shapeless / factory ingredients with counts
 * @param tech         science tech id required to craft, or null
 * @param techOptional the recipe may be crafted without the tech (hunting bow)
 * @param category     recipe book category
 * @param tier         tier shown in the book
 */
public record RecipeDef(String id, String resultId, Material resultVanilla, int amount, Station station,
                        List<String> shape, Map<Character, Ingredient> keys, List<Ingredient.Counted> ingredients,
                        String tech, boolean techOptional, String category, int tier) {

    public enum Station { WORKBENCH, FACTORY }

    public boolean shaped() {
        return !shape.isEmpty();
    }

    /** How many units of each ingredient one craft consumes (shaped letters are counted). */
    public Map<Ingredient, Integer> totals() {
        Map<Ingredient, Integer> totals = new LinkedHashMap<>();
        if (shaped()) {
            for (String row : shape) {
                for (char c : row.toCharArray()) {
                    Ingredient ing = keys.get(c);
                    if (ing != null) totals.merge(ing, 1, Integer::sum);
                }
            }
        } else {
            for (Ingredient.Counted c : ingredients) totals.merge(c.ingredient(), c.count(), Integer::sum);
        }
        return totals;
    }

    /** Required count per custom item id. */
    public Map<String, Integer> customTotals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Map.Entry<Ingredient, Integer> e : totals().entrySet()) {
            if (e.getKey() instanceof Ingredient.Custom c) totals.merge(c.id(), e.getValue(), Integer::sum);
        }
        return totals;
    }

    public int slotCount() {
        return totals().values().stream().mapToInt(Integer::intValue).sum();
    }
}
