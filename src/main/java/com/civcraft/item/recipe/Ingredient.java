package com.civcraft.item.recipe;

import java.util.List;
import org.bukkit.Material;

/** One recipe slot: a custom item id or a set of acceptable vanilla materials. */
public sealed interface Ingredient {

    /** Text used in configs and errors. */
    String token();

    /** A CivCraft item, matched by its PDC id. */
    record Custom(String id) implements Ingredient {
        @Override
        public String token() {
            return id;
        }
    }

    /**
     * Plain vanilla items (never custom items with the same base material).
     *
     * @param materials accepted materials, the first one is shown in the recipe book
     * @param group     group name for "#group" ingredients, or null for a single material
     */
    record Vanilla(List<Material> materials, String group) implements Ingredient {

        public Vanilla {
            if (materials.isEmpty()) throw new IllegalArgumentException("empty vanilla ingredient");
            materials = List.copyOf(materials);
        }

        public boolean accepts(Material material) {
            return materials.contains(material);
        }

        @Override
        public String token() {
            return group != null ? "#" + group : materials.getFirst().getKey().toString();
        }
    }

    /** An ingredient with a count (shapeless and factory recipes). */
    record Counted(Ingredient ingredient, int count) {
        public Counted {
            if (count < 1) throw new IllegalArgumentException("count must be >= 1");
        }
    }
}
