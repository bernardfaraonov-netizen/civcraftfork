package com.civcraft.item.book;

import com.civcraft.core.text.Messages;
import com.civcraft.gui.Items;
import com.civcraft.gui.Menu;
import com.civcraft.gui.PagedMenu;
import com.civcraft.item.ItemRegistry;
import com.civcraft.item.ItemRenderer;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.recipe.Ingredient;
import com.civcraft.item.recipe.RecipeDef;
import com.civcraft.item.recipe.RecipeService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The recipe book (spec 04 §4.1: {@code /res book} → «Рецепты крафтов»): categories by kind and tier, a
 * paged list of recipes, and a detail view with the 3×3 grid (or the Factory's ingredient list), the tech
 * requirement and links to the recipes of every custom ingredient.
 */
public final class RecipeBook {

    private static final List<String> CATEGORY_ORDER = List.of("materials", "catalysts", "gear", "special", "space");
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int[] BUTTONS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32,
            33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int[] FACTORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30};

    private final ItemRegistry registry;
    private final ItemRenderer renderer;
    private final RecipeService recipes;
    private final Messages messages;
    private final Function<String, String> techName;

    /** A (category, tier) group of recipes shown as one button. */
    private record Group(String category, int tier, List<RecipeDef> recipes) {
    }

    public RecipeBook(ItemRegistry registry, ItemRenderer renderer, RecipeService recipes, Messages messages,
                      Function<String, String> techName) {
        this.registry = registry;
        this.renderer = renderer;
        this.recipes = recipes;
        this.messages = messages;
        this.techName = techName;
    }

    public void open(Player player) {
        new MainMenu().open(player);
    }

    /** Opens a list of recipes whose result name or id contains {@code query}. */
    public void search(Player player, String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<RecipeDef> found = new ArrayList<>();
        for (RecipeDef r : sorted(recipes.all())) {
            String name = PlainTextComponentSerializer.plainText().serialize(resultName(r)).toLowerCase(Locale.ROOT);
            String id = r.resultId() != null ? r.resultId() : r.resultVanilla().getKey().getKey();
            if (name.contains(q) || id.contains(q)) found.add(r);
        }
        if (found.isEmpty()) {
            messages.send(player, "items.book.not-found", Messages.arg("query", query));
            return;
        }
        if (found.size() == 1) {
            new DetailMenu(found.getFirst(), null).open(player);
            return;
        }
        new ListMenu(messages.component("items.book.search-title", Messages.arg("query", query)), found, null).open(player);
    }

    /** Opens the recipe of a custom item or vanilla key ("minecraft:hopper"); false when there is none. */
    public boolean show(Player player, String itemId) {
        List<RecipeDef> list = recipes.producing(itemId);
        if (list.isEmpty()) return false;
        new DetailMenu(list.getFirst(), null).open(player);
        return true;
    }

    // ------------------------------------------------------------------------------------ helpers

    private List<RecipeDef> sorted(java.util.Collection<RecipeDef> in) {
        List<RecipeDef> list = new ArrayList<>(in);
        list.sort(Comparator.comparingInt((RecipeDef r) -> categoryIndex(r.category()))
                .thenComparingInt(RecipeDef::tier)
                .thenComparing(r -> r.resultId() != null ? r.resultId() : r.resultVanilla().name()));
        return list;
    }

    private static int categoryIndex(String category) {
        int i = CATEGORY_ORDER.indexOf(category);
        return i < 0 ? CATEGORY_ORDER.size() : i;
    }

    private List<Group> groups() {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (RecipeDef r : sorted(recipes.all())) {
            int tier = r.category().equals("materials") || r.category().equals("gear") || r.category().equals("catalysts")
                    ? r.tier() : -1;
            groups.computeIfAbsent(r.category() + ":" + tier, k -> new Group(r.category(), tier, new ArrayList<>()))
                    .recipes().add(r);
        }
        return new ArrayList<>(groups.values());
    }

    private Component categoryName(String category) {
        String key = "items.category." + category;
        return messages.has(key) ? messages.component(key) : Component.text(category);
    }

    private Component resultName(RecipeDef r) {
        if (r.resultId() != null) return renderer.name(registry.get(r.resultId()));
        return Component.translatable(r.resultVanilla().translationKey());
    }

    private ItemStack resultIcon(RecipeDef r) {
        if (r.resultId() != null) return renderer.create(registry.get(r.resultId()), r.amount());
        return ItemStack.of(r.resultVanilla(), r.amount());
    }

    private static ItemStack withLore(ItemStack stack, List<Component> extra) {
        List<Component> lore = new ArrayList<>();
        ItemLore existing = stack.getData(DataComponentTypes.LORE);
        if (existing != null) lore.addAll(existing.lines());
        for (Component c : extra) lore.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        return stack;
    }

    private Component techLine(Player viewer, RecipeDef r) {
        if (r.tech() == null) return messages.component("items.book.no-tech");
        boolean ok = recipes.allowed(viewer, r);
        return messages.component(ok ? "items.book.tech-ok" : "items.book.tech-missing",
                Messages.arg("tech", techName.apply(r.tech())));
    }

    private ItemStack ingredientIcon(Ingredient ingredient, int count) {
        ItemStack icon;
        List<Component> lore = new ArrayList<>();
        switch (ingredient) {
            case Ingredient.Custom c -> {
                icon = renderer.create(registry.get(c.id()), 1);
                if (!recipes.producing(c.id()).isEmpty()) lore.add(messages.component("items.book.click-recipe"));
            }
            case Ingredient.Vanilla v -> {
                icon = ItemStack.of(v.materials().getFirst());
                if (v.materials().size() > 1) {
                    lore.add(messages.component("items.book.any-of"));
                    for (Material m : v.materials()) {
                        lore.add(Component.text(" • ").append(Component.translatable(m.translationKey())));
                    }
                }
            }
        }
        if (count > 1) {
            if (count <= icon.getMaxStackSize()) icon.setAmount(count);
            lore.add(messages.component("items.book.amount", Messages.arg("amount", count)));
        }
        return withLore(icon, lore);
    }

    private Consumer<InventoryClickEvent> ingredientClick(Player viewer, Ingredient ingredient, Menu back) {
        return e -> {
            if (ingredient instanceof Ingredient.Custom c) {
                List<RecipeDef> list = recipes.producing(c.id());
                if (!list.isEmpty()) new DetailMenu(list.getFirst(), back).open(viewer);
            }
        };
    }

    private ItemStack backButton() {
        return Items.of(Material.ARROW).name(messages.component("items.book.back")).build();
    }

    // ------------------------------------------------------------------------------------ menus

    private final class MainMenu extends Menu {

        MainMenu() {
            super(6, messages.component("items.book.title"));
        }

        @Override
        protected void render(Player viewer) {
            List<Group> groups = groups();
            for (int i = 0; i < groups.size() && i < BUTTONS.length; i++) {
                Group g = groups.get(i);
                Component name = g.tier() >= 0
                        ? messages.component("items.book.group-tier", Messages.arg("category", categoryName(g.category())),
                        Messages.arg("tier", g.tier() == 0 ? "T0" : "T" + g.tier()))
                        : categoryName(g.category());
                ItemStack icon = resultIcon(g.recipes().getFirst());
                icon.setAmount(1);
                icon.setData(DataComponentTypes.ITEM_NAME, name.decoration(TextDecoration.ITALIC, false));
                icon.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                        messages.component("items.book.group-count", Messages.arg("count", g.recipes().size()))
                                .decoration(TextDecoration.ITALIC, false))));
                Menu self = this;
                set(BUTTONS[i], icon, e -> new ListMenu(name, g.recipes(), self).open(viewer));
            }
            set(49, Items.of(Material.KNOWLEDGE_BOOK).name(messages.component("items.book.help-title"))
                    .lore(messages.lines("items.book.help")).build());
            fill(Items.filler());
        }
    }

    private final class ListMenu extends PagedMenu<RecipeDef> {

        private final List<RecipeDef> list;
        private final Menu back;

        ListMenu(Component title, List<RecipeDef> list, Menu back) {
            super(title);
            this.list = list;
            this.back = back;
        }

        @Override
        protected List<RecipeDef> entries(Player viewer) {
            return list;
        }

        @Override
        protected ItemStack icon(Player viewer, RecipeDef entry) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(techLine(viewer, entry));
            if (entry.station() == RecipeDef.Station.FACTORY) lore.add(messages.component("items.book.station-factory"));
            lore.add(messages.component("items.book.click-open"));
            return withLore(resultIcon(entry), lore);
        }

        @Override
        protected Consumer<InventoryClickEvent> action(Player viewer, RecipeDef entry) {
            Menu self = this;
            return e -> new DetailMenu(entry, self).open(viewer);
        }

        @Override
        protected void renderFooter(Player viewer) {
            if (back != null) set(46, backButton(), e -> back.open(viewer));
        }
    }

    private final class DetailMenu extends Menu {

        private final RecipeDef recipe;
        private final Menu back;

        DetailMenu(RecipeDef recipe, Menu back) {
            super(6, messages.component("items.book.recipe-title", Messages.arg("item", resultName(recipe))));
            this.recipe = recipe;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            Menu self = this;
            if (recipe.station() == RecipeDef.Station.FACTORY) {
                int i = 0;
                for (Map.Entry<Ingredient, Integer> e : recipe.totals().entrySet()) {
                    if (i >= FACTORY_SLOTS.length) break;
                    set(FACTORY_SLOTS[i++], ingredientIcon(e.getKey(), e.getValue()),
                            ingredientClick(viewer, e.getKey(), self));
                }
                set(40, withLore(resultIcon(recipe), List.of(messages.component("items.book.station-factory"))));
            } else if (recipe.shaped()) {
                for (int row = 0; row < recipe.shape().size(); row++) {
                    String line = recipe.shape().get(row);
                    for (int col = 0; col < line.length(); col++) {
                        Ingredient ing = recipe.keys().get(line.charAt(col));
                        if (ing == null) continue;
                        set(GRID[row * 3 + col], ingredientIcon(ing, 1), ingredientClick(viewer, ing, self));
                    }
                }
                set(23, Items.of(Material.CRAFTING_TABLE).name(messages.component("items.book.shaped")).build());
                set(25, resultIcon(recipe));
            } else {
                int slot = 0;
                for (Ingredient.Counted c : recipe.ingredients()) {
                    for (int n = 0; n < c.count() && slot < GRID.length; n++) {
                        set(GRID[slot++], ingredientIcon(c.ingredient(), 1), ingredientClick(viewer, c.ingredient(), self));
                    }
                }
                set(23, Items.of(Material.CRAFTING_TABLE).name(messages.component("items.book.shapeless")).build());
                set(25, resultIcon(recipe));
            }
            set(43, Items.of(Material.ENCHANTED_BOOK).name(techLine(viewer, recipe)).build());
            List<RecipeDef> alternatives = recipes.producing(recipe.resultId() != null ? recipe.resultId()
                    : recipe.resultVanilla().getKey().toString());
            if (alternatives.size() > 1) {
                int index = alternatives.indexOf(recipe);
                RecipeDef next = alternatives.get((index + 1) % alternatives.size());
                set(51, Items.of(Material.SPECTRAL_ARROW).name(messages.component("items.book.alternative",
                        Messages.arg("index", index + 1), Messages.arg("total", alternatives.size()))).build(),
                        e -> new DetailMenu(next, back).open(viewer));
            }
            if (recipe.resultId() != null) {
                List<RecipeDef> uses = recipes.using(recipe.resultId());
                if (!uses.isEmpty()) {
                    set(52, Items.of(Material.BOOK).name(messages.component("items.book.used-in",
                            Messages.arg("count", uses.size()))).build(),
                            e -> new ListMenu(messages.component("items.book.used-in-title",
                                    Messages.arg("item", resultName(recipe))), sorted(uses), self).open(viewer));
                }
            }
            if (back != null) set(45, backButton(), e -> back.open(viewer));
            fill(Items.filler());
        }
    }
}
