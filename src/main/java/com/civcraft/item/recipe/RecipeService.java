package com.civcraft.item.recipe;

import com.civcraft.item.ItemData;
import com.civcraft.item.ItemKeys;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemFlag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;

/**
 * Registers CivCraft workbench recipes with Bukkit ({@code civcraft:<id>}, custom ingredients as
 * {@link RecipeChoice.ExactChoice}) and guards crafting:
 * <ul>
 *   <li>custom items never work as ingredients of vanilla recipes;</li>
 *   <li>a custom item may not stand in for a vanilla ingredient of a CivCraft recipe (count check);</li>
 *   <li>tech-gated recipes ask the recipe gate (science module) before showing a result.</li>
 * </ul>
 */
public final class RecipeService implements Listener {

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final Logger logger;
    private final Map<String, RecipeDef> recipes = new LinkedHashMap<>();
    private final Map<NamespacedKey, RecipeDef> byKey = new HashMap<>();
    private final List<NamespacedKey> registered = new ArrayList<>();
    private BiPredicate<Player, String> gate = (p, id) -> true;
    private BiConsumer<Player, RecipeDef> onDenied = (p, r) -> { };

    public RecipeService(ItemRegistry registry, ItemRenderer renderer, Logger logger) {
        this.registry = registry;
        this.renderer = renderer;
        this.logger = logger;
    }

    // ------------------------------------------------------------------------------------ registry

    public void add(RecipeDef def) {
        RecipeDef previous = recipes.put(def.id(), def);
        if (previous != null) logger.warning("Recipe " + def.id() + " defined twice; the later definition wins");
        byKey.put(key(def), def);
    }

    public RecipeDef get(String id) {
        return recipes.get(id);
    }

    public RecipeDef get(NamespacedKey key) {
        return byKey.get(key);
    }

    public Collection<RecipeDef> all() {
        return Collections.unmodifiableCollection(recipes.values());
    }

    /** Recipes producing the given custom id or vanilla key ("minecraft:hopper"). */
    public List<RecipeDef> producing(String id) {
        List<RecipeDef> out = new ArrayList<>();
        for (RecipeDef r : recipes.values()) {
            String result = r.resultId() != null ? r.resultId() : r.resultVanilla().getKey().toString();
            if (result.equals(id)) out.add(r);
        }
        return out;
    }

    /** Recipes that use the given custom item as an ingredient. */
    public List<RecipeDef> using(String customId) {
        List<RecipeDef> out = new ArrayList<>();
        for (RecipeDef r : recipes.values()) {
            if (r.customTotals().containsKey(customId)) out.add(r);
        }
        return out;
    }

    public void setGate(BiPredicate<Player, String> gate) {
        this.gate = gate;
    }

    public void onDenied(BiConsumer<Player, RecipeDef> handler) {
        this.onDenied = handler;
    }

    public boolean allowed(Player player, RecipeDef def) {
        return gate.test(player, def.id());
    }

    public static NamespacedKey key(RecipeDef def) {
        return ItemKeys.key(def.id());
    }

    // ------------------------------------------------------------------------------------ Bukkit

    public ItemStack result(RecipeDef def) {
        if (def.resultId() != null) return renderer.create(registry.get(def.resultId()), def.amount());
        return ItemStack.of(def.resultVanilla(), def.amount());
    }

    /** Registers all workbench recipes with the server (idempotent). */
    public int registerAll() {
        int count = 0;
        for (RecipeDef def : recipes.values()) {
            if (def.station() != RecipeDef.Station.WORKBENCH) continue;
            NamespacedKey key = key(def);
            if (Bukkit.getRecipe(key) != null) Bukkit.removeRecipe(key, false);
            try {
                if (Bukkit.addRecipe(toBukkit(def), false)) {
                    registered.add(key);
                    count++;
                } else {
                    logger.warning("Bukkit refused recipe " + key);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                logger.warning("Cannot register recipe " + key + ": " + e.getMessage());
            }
        }
        return count;
    }

    public void unregisterAll() {
        for (NamespacedKey key : registered) Bukkit.removeRecipe(key, false);
        registered.clear();
    }

    /** Removes vanilla recipes (keys without namespace mean minecraft:). Returns how many were removed. */
    public int removeVanilla(List<String> keys) {
        int removed = 0;
        for (String k : keys) {
            NamespacedKey key = NamespacedKey.fromString(k.contains(":") ? k : "minecraft:" + k);
            if (key != null && Bukkit.removeRecipe(key, false)) removed++;
        }
        return removed;
    }

    private CraftingRecipe toBukkit(RecipeDef def) {
        NamespacedKey key = key(def);
        ItemStack result = result(def);
        CraftingRecipe recipe;
        if (def.shaped()) {
            ShapedRecipe shaped = new ShapedRecipe(key, result);
            shaped.shape(def.shape().toArray(String[]::new));
            for (Map.Entry<Character, Ingredient> e : def.keys().entrySet()) {
                shaped.setIngredient(e.getKey(), choice(e.getValue()));
            }
            recipe = shaped;
        } else {
            ShapelessRecipe shapeless = new ShapelessRecipe(key, result);
            for (Ingredient.Counted c : def.ingredients()) {
                RecipeChoice choice = choice(c.ingredient());
                for (int i = 0; i < c.count(); i++) shapeless.addIngredient(choice.clone());
            }
            recipe = shapeless;
        }
        recipe.setGroup("civcraft_" + def.category());
        recipe.setCategory(def.category().equals("gear") ? CraftingBookCategory.EQUIPMENT : CraftingBookCategory.MISC);
        return recipe;
    }

    private RecipeChoice choice(Ingredient ingredient) {
        return switch (ingredient) {
            case Ingredient.Custom c -> new RecipeChoice.ExactChoice(renderer.create(registry.get(c.id()), 1));
            case Ingredient.Vanilla v -> new RecipeChoice.MaterialChoice(v.materials());
        };
    }

    // ------------------------------------------------------------------------------------ matching

    private static RecipeMatcher.Cell[] cells(ItemStack[] matrix) {
        RecipeMatcher.Cell[] cells = new RecipeMatcher.Cell[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            ItemStack s = matrix[i];
            cells[i] = ItemData.empty(s) ? null : new RecipeMatcher.Cell(s.getType(), ItemData.id(s));
        }
        return cells;
    }

    /**
     * The CivCraft recipe the grid really matches, given the recipe the server picked (by base material).
     * Returns null when the grid is not a CivCraft recipe. Never resolves a grid the server did not match
     * at all: taking a result without a server recipe would hand the ingredients back (dupe).
     */
    public RecipeDef resolve(Recipe serverRecipe, ItemStack[] matrix) {
        if (serverRecipe == null || (matrix.length != 4 && matrix.length != 9)) return null;
        RecipeMatcher.Cell[] grid = cells(matrix);
        if (serverRecipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(ItemKeys.NAMESPACE)) {
            RecipeDef picked = byKey.get(keyed.getKey());
            if (picked != null && RecipeMatcher.matches(picked, grid)) return picked;
        } else if (!anyCustom(matrix)) {
            return null;
        }
        for (RecipeDef def : recipes.values()) {
            if (def.station() == RecipeDef.Station.WORKBENCH && RecipeMatcher.matches(def, grid)) return def;
        }
        return null;
    }

    private static boolean anyCustom(ItemStack[] matrix) {
        for (ItemStack stack : matrix) if (ItemData.id(stack) != null) return true;
        return false;
    }

    private boolean anyCustomVanillaBlocked(ItemStack[] matrix) {
        for (ItemStack stack : matrix) {
            if (ItemData.id(stack) == null) continue;
            ItemDef def = registry.def(stack);
            if (def == null || !def.has(ItemFlag.VANILLA_CRAFTING)) return true;
        }
        return false;
    }

    private static boolean ours(Recipe recipe) {
        return recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(ItemKeys.NAMESPACE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        ItemStack[] matrix = event.getInventory().getMatrix();
        RecipeDef def = resolve(recipe, matrix);
        if (def != null) {
            HumanEntity viewer = event.getView().getPlayer();
            if (viewer instanceof Player player && !allowed(player, def)) {
                event.getInventory().setResult(null);
                onDenied.accept(player, def);
            } else {
                event.getInventory().setResult(result(def));
            }
            return;
        }
        if (ours(recipe) || anyCustomVanillaBlocked(matrix)) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        ItemStack[] matrix = event.getInventory().getMatrix();
        RecipeDef def = resolve(recipe, matrix);
        if (def != null) {
            if (event.getWhoClicked() instanceof Player p && !allowed(p, def)) event.setCancelled(true);
            return;
        }
        if (ours(recipe) || anyCustomVanillaBlocked(matrix)) event.setCancelled(true);
    }

    /** Crafters have no player to check techs for: tech-gated CivCraft recipes never run in them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        ItemStack[] contents = event.getBlock().getState(false) instanceof Crafter crafter
                ? crafter.getInventory().getContents() : new ItemStack[0];
        CraftingRecipe recipe = event.getRecipe();
        RecipeDef def = resolve(recipe, contents);
        if (def != null) {
            if (def.tech() != null && !def.techOptional()) event.setCancelled(true);
            else event.setResult(result(def));
            return;
        }
        if (ours(recipe) || anyCustomVanillaBlocked(contents)) event.setCancelled(true);
    }
}
