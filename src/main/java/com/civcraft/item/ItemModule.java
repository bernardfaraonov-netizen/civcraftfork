package com.civcraft.item;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.command.AdminRegistry;
import com.civcraft.core.text.Messages;
import com.civcraft.item.book.RecipeBook;
import com.civcraft.item.def.ItemDef;
import com.civcraft.item.def.ItemDefParser;
import com.civcraft.item.def.ItemKind;
import com.civcraft.item.enhance.EnchantService;
import com.civcraft.item.enhance.RepairService;
import com.civcraft.item.enhance.SharpeningService;
import com.civcraft.item.gear.CombatHooks;
import com.civcraft.item.gear.CombatListener;
import com.civcraft.item.gear.DeathListener;
import com.civcraft.item.gear.EnchantEffects;
import com.civcraft.item.gear.GearService;
import com.civcraft.item.gear.Realms;
import com.civcraft.item.gear.ToolListener;
import com.civcraft.item.guard.ItemGuardListener;
import com.civcraft.item.recipe.RecipeDef;
import com.civcraft.item.recipe.RecipeParser;
import com.civcraft.item.recipe.RecipeService;
import com.civcraft.model.Civilization;
import com.civcraft.science.ResearchApi;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * The item module (spec 04 §4–§8, spec 01 §4.1/§5.1, spec 03 §5.13/§8–§10): custom item registry,
 * recipes, gear and combat, sharpening, enchantments, repair prices, SoulBound, starting kit and the
 * recipe book. Other modules use it through {@link ItemApi} ({@code civ.api(ItemApi.class)}) and, for
 * the services below, {@code civ.module(ItemModule.class)}.
 */
public final class ItemModule implements Module, ItemApi {

    public static final String ID = "items";

    private CivCraft civ;
    private final ItemRegistry registry = new ItemRegistry();
    private ItemRenderer renderer;
    private ItemRules rules;
    private Map<String, String> techs = Map.of();
    private ItemDefParser.Context parseContext;
    private RecipeService recipes;
    private SharpeningService sharpening;
    private EnchantService enchants;
    private RepairService repair;
    private final CombatHooks combat = new CombatHooks();
    private GearService gear;
    private EnchantEffects effects;
    private Realms realms;
    private RecipeBook book;
    private ItemGuardListener guard;
    private final Map<UUID, ItemProfile> profiles = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, BiConsumer<Player, PlayerInteractEvent>> handlers = new ConcurrentHashMap<>();
    private BiPredicate<Player, String> techCheck = this::defaultTechCheck;
    private BiPredicate<Player, String> recipeGate = this::defaultRecipeGate;
    private boolean enabled;

    @Override
    public String id() {
        return ID;
    }

    // ================================================================================== lifecycle

    @Override
    public void load(CivCraft civ) {
        this.civ = civ;
        civ.messages().include(ID);
        YamlConfiguration main = civ.balance().file(ID);
        Map<String, String> t = new LinkedHashMap<>();
        ConfigurationSection ts = main.getConfigurationSection("techs");
        if (ts != null) for (String k : ts.getKeys(false)) t.put(k, ts.getString(k));
        techs = Map.copyOf(t);
        rules = new ItemRules(main, techs);

        Map<Integer, String> tierRarity = new HashMap<>();
        ConfigurationSection tr = main.getConfigurationSection("tier-rarity");
        if (tr != null) for (String k : tr.getKeys(false)) tierRarity.put(Integer.parseInt(k), tr.getString(k));
        Map<Integer, Integer> durability = new HashMap<>();
        ConfigurationSection dm = main.getConfigurationSection("durability.default-max");
        if (dm != null) for (String k : dm.getKeys(false)) durability.put(Integer.parseInt(k), dm.getInt(k));
        parseContext = new ItemDefParser.Context(techs, tierRarity, durability,
                main.getDouble("durability.death-loss", 0.25));
        ConfigurationSection as = main.getConfigurationSection("aliases");
        if (as != null) for (String k : as.getKeys(false)) aliases.put(k, as.getString(k));
        Map<String, String> rarityColors = new LinkedHashMap<>();
        ConfigurationSection rc = main.getConfigurationSection("rarity-colors");
        if (rc != null) for (String k : rc.getKeys(false)) rarityColors.put(k, rc.getString(k));

        renderer = new ItemRenderer(registry, civ.messages(), rarityColors, this::techName);
        recipes = new RecipeService(registry, renderer, civ.logger());
        List<YamlConfiguration> files = new ArrayList<>();
        for (String name : main.getStringList("files")) files.add(civ.balance().file(name));
        loadDefinitions(main.getStringList("files"), files);

        sharpening = new SharpeningService(registry, renderer, this::profile, this::saveProfile);
        sharpening.configure(main.getConfigurationSection("sharpening"));
        repair = new RepairService(registry, renderer);
        repair.configure(main.getConfigurationSection("repair"));
        enchants = new EnchantService(registry, renderer, civ.logger());
        enchants.load(main.getConfigurationSection("enchantments"), main.getStringList("soulbound-forbidden-materials"));
        book = new RecipeBook(registry, renderer, recipes, civ.messages(), this::techName);

        civ.store().createCollection(ItemProfile.COLLECTION);
        for (ItemProfile p : civ.store().loadAll(ItemProfile.COLLECTION, ItemProfile.class)) {
            if (p.uuid() != null) profiles.put(p.uuid(), p);
        }
        civ.logger().info("Items: " + registry.all().size() + " items, " + recipes.all().size() + " recipes, "
                + enchants.all().size() + " enchantments");
    }

    private void loadDefinitions(List<String> names, List<YamlConfiguration> files) {
        List<String> errors = new ArrayList<>();
        for (YamlConfiguration f : files) RecipeParser.parseGroups(f.getConfigurationSection("groups"), registry.groups(), errors);
        ItemDefParser parser = new ItemDefParser(parseContext);
        for (int i = 0; i < files.size(); i++) {
            for (ItemDef def : parser.parse(names.get(i), files.get(i).getConfigurationSection("items"))) {
                if (registry.exists(def.id())) errors.add(names.get(i) + ": item " + def.id() + " defined twice");
                registry.register(def);
            }
        }
        errors.addAll(parser.errors());
        RecipeParser rp = new RecipeParser(registry.groups(), registry::get, techs);
        for (int i = 0; i < files.size(); i++) {
            rp.parseRecipes(names.get(i), files.get(i).getConfigurationSection("recipes")).forEach(recipes::add);
            rp.parseCompression(names.get(i), files.get(i).getConfigurationSection("compression")).forEach(recipes::add);
        }
        errors.addAll(rp.errors());
        for (String e : errors) civ.logger().warning("Item definitions: " + e);
    }

    @Override
    public void enable(CivCraft civ) {
        int removed = recipes.removeVanilla(rules.removedRecipes);
        int added = recipes.registerAll();
        Bukkit.updateRecipes();
        civ.logger().info("Items: registered " + added + " recipes, removed " + removed + " vanilla recipes");
        recipes.setGate((p, id) -> recipeGate.test(p, id));
        recipes.onDenied((p, r) -> civ.messages().actionBar(p, "items.craft.no-tech",
                Messages.arg("tech", techName(r.tech()))));

        realms = new Realms(civ, rules);
        gear = new GearService(registry, rules, combat, civ.tasks(), this::highTech, this::civStat, realms);
        effects = new EnchantEffects(civ, rules, realms);
        guard = new ItemGuardListener(registry, renderer, rules, civ.messages(), handlers);
        civ.listen(recipes);
        civ.listen(guard);
        civ.listen(gear);
        civ.listen(effects);
        civ.listen(new CombatListener(registry, rules, gear, combat, effects, (p, tech) -> techCheck(p, tech),
                this::highTech, this::civStat, realms, sharpening.maxLevel(com.civcraft.item.def.SharpenType.ATTACK)));
        civ.listen(new DeathListener(civ, registry, renderer, rules, combat, civ.messages()));
        civ.listen(new ToolListener(registry));
        civ.listen(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                guard.forget(event.getPlayer().getUniqueId());
            }
        });
        effects.start();
        civ.clock().everySecond("items-gear", () -> gear.tickAll(Bukkit.getOnlinePlayers()));

        onUse("guide_book", (player, event) -> player.performCommand("res book"));

        ItemCommands commands = new ItemCommands(this, civ.messages());
        civ.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> commands.registerPlayerCommands(event.registrar()));
        AdminRegistry.add(commands.adminNode());
        validateTechs();
        enabled = true;
    }

    private void validateTechs() {
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        if (research == null) return;
        java.util.Set<String> unknown = new java.util.TreeSet<>();
        for (ItemDef d : registry.all()) if (d.tech() != null && !research.techExists(d.tech())) unknown.add(d.tech());
        for (RecipeDef r : recipes.all()) if (r.tech() != null && !research.techExists(r.tech())) unknown.add(r.tech());
        if (!unknown.isEmpty()) {
            civ.logger().warning("Items: tech ids unknown to the science module (fix balance/items.yml `techs`): " + unknown);
        }
    }

    @Override
    public void disable(CivCraft civ) {
        if (effects != null) effects.stop();
        if (recipes != null) recipes.unregisterAll();
        enabled = false;
    }

    // ================================================================================== definitions API

    public ItemRegistry registry() {
        return registry;
    }

    public ItemDef def(String id) {
        return registry.get(resolve(id));
    }

    /**
     * Canonical id for an id or alias ({@code aliases:} in balance/items.yml, {@code artifact_<id>});
     * returns the input when nothing matches.
     */
    public String resolve(String id) {
        if (id == null || registry.exists(id)) return id;
        String alias = aliases.get(id);
        if (alias != null && registry.exists(alias)) return alias;
        if (id.startsWith("artifact_") && registry.exists(id.substring(9))) return id.substring(9);
        return id;
    }

    /** Base id of a "{@code <id>_once}" request, or null. */
    private String singleUseBase(String id) {
        if (id == null || !id.endsWith("_once")) return null;
        String base = resolve(id.substring(0, id.length() - 5));
        return registry.exists(base) ? base : null;
    }

    /** A one-shot copy of an item (artifacts from ruins): same id, flagged {@code civcraft:single_use}. */
    public ItemStack createSingleUse(String id, int amount) {
        ItemStack stack = create(id, amount);
        ItemData.markSingleUse(stack);
        renderer.render(stack);
        return stack;
    }

    public ItemDef def(ItemStack stack) {
        return registry.def(stack);
    }

    public Collection<ItemDef> defs() {
        return registry.all();
    }

    public ItemRenderer renderer() {
        return renderer;
    }

    public ItemRules rules() {
        return rules;
    }

    /**
     * Adds item and recipe definitions from another module's YAML (same format as the item balance
     * files: {@code groups}, {@code items}, {@code recipes}, {@code compression}). Call from {@code load()}
     * of a module registered after this one; later calls register the recipes with the server at once.
     */
    public void define(String sourceName, YamlConfiguration root) {
        int before = recipes.all().size();
        loadDefinitions(List.of(sourceName), List.of(root));
        if (enabled && recipes.all().size() != before) {
            recipes.unregisterAll();
            recipes.registerAll();
            Bukkit.updateRecipes();
        }
    }

    // ================================================================================== services

    public RecipeService recipes() {
        return recipes;
    }

    public RecipeDef recipe(String id) {
        return recipes.get(id);
    }

    public SharpeningService sharpening() {
        return sharpening;
    }

    public EnchantService enchants() {
        return enchants;
    }

    public RepairService repair() {
        return repair;
    }

    /** Combat extension points (attack/defense modifiers, armor bonuses, durability protection). */
    public CombatHooks combat() {
        return combat;
    }

    /** Worn gear: full-set checks and attribute refresh (null before enable). */
    public GearService gear() {
        return gear;
    }

    /** Air Valley / dungeon checks used by the gear rules. */
    public Realms realms() {
        return realms;
    }

    /** Custom enchantment effects; {@code rollPunchout} is used by the war/structure damage code. */
    public EnchantEffects effects() {
        return effects;
    }

    public void openRecipeBook(Player player) {
        book.open(player);
    }

    /** {@code /res cmat <name>}: search recipes by item name. */
    public void searchRecipes(Player player, String query) {
        book.search(player, query);
    }

    /** Opens the recipe of an item id; false when the item has no recipe. */
    public boolean showRecipe(Player player, String itemId) {
        return book.show(player, itemId);
    }

    // ================================================================================== tech gates

    /**
     * Replaces the recipe gate: (player, recipe id) → may craft. The default allows recipes without a
     * tech and asks {@link #techCheck} for the others (tech-optional recipes always pass).
     */
    public void setRecipeGate(BiPredicate<Player, String> gate) {
        this.recipeGate = gate == null ? this::defaultRecipeGate : gate;
    }

    /** Replaces the tech check used for crafting and for full weapon damage: (player, tech id) → researched. */
    public void setTechCheck(BiPredicate<Player, String> check) {
        this.techCheck = check == null ? this::defaultTechCheck : check;
    }

    public boolean techCheck(Player player, String tech) {
        return tech == null || techCheck.test(player, tech);
    }

    private boolean defaultTechCheck(Player player, String tech) {
        if (tech == null) return true;
        ResearchApi research = civ.apiOrNull(ResearchApi.class);
        if (research == null) return true;
        Civilization c = civ.state().civOf(player);
        return c != null && research.hasTech(c, tech);
    }

    private boolean defaultRecipeGate(Player player, String recipeId) {
        RecipeDef def = recipes.get(recipeId);
        return def == null || def.tech() == null || def.techOptional() || techCheck(player, def.tech());
    }

    /** Sharpening above +2 works only with the War Lab tech (spec 04 §6.1). */
    private boolean highTech(Player player) {
        return rules.highLevelTech == null || techCheck(player, rules.highLevelTech);
    }

    /** Value of a civ stat (StatService) for the player's civilization, 0 without a civ. */
    public double civStat(Player player, String stat) {
        if (stat == null || stat.isEmpty()) return 0;
        Civilization c = civ.state().civOf(player);
        return c == null ? 0 : civ.stats().civ(c).get(stat);
    }

    public String techName(String tech) {
        if (tech == null) return "";
        ResearchApi research = civ == null ? null : civ.apiOrNull(ResearchApi.class);
        if (research != null && research.techExists(tech)) return research.techName(tech);
        return tech;
    }

    /** Science tech id for a symbolic name of {@code balance/items.yml techs}. */
    public String tech(String symbolic) {
        return techs.getOrDefault(symbolic, symbolic);
    }

    // ================================================================================== profiles

    public ItemProfile profile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, ItemProfile::new);
    }

    ItemProfile profile(Player player) {
        return profile(player.getUniqueId());
    }

    void saveProfile(ItemProfile profile) {
        civ.saves().save(ItemProfile.COLLECTION, profile);
    }

    // ================================================================================== ItemApi

    @Override
    public ItemStack create(String id, int amount) {
        ItemDef def = registry.get(resolve(id));
        if (def != null) return renderer.create(def, amount);
        String once = singleUseBase(id);
        if (once != null) return createSingleUse(once, amount);
        if (id != null && id.contains(":")) {
            Material m = Material.matchMaterial(id);
            if (m != null && m.isItem() && !m.isAir()) return ItemStack.of(m, Math.max(1, Math.min(m.getMaxStackSize(), amount)));
        }
        throw new IllegalArgumentException("Unknown item " + id);
    }

    @Override
    public String id(ItemStack stack) {
        return ItemData.id(stack);
    }

    @Override
    public boolean exists(String id) {
        return registry.exists(resolve(id)) || singleUseBase(id) != null;
    }

    @Override
    public boolean is(ItemStack stack, String id) {
        return stack != null && id != null && resolve(id).equals(ItemData.id(stack));
    }

    @Override
    public Component displayName(String id) {
        ItemDef def = registry.get(resolve(id));
        if (def != null) return renderer.name(def);
        if (id != null && id.contains(":")) {
            Material m = Material.matchMaterial(id);
            if (m != null) return Component.translatable(m.translationKey());
        }
        return Component.text(String.valueOf(id));
    }

    @Override
    public void onUse(String id, BiConsumer<Player, PlayerInteractEvent> handler) {
        handlers.put(resolve(id), handler);
    }

    private boolean matches(ItemStack stack, String id) {
        if (ItemData.empty(stack)) return false;
        if (id.contains(":")) {
            return ItemData.id(stack) == null && stack.getType().getKey().toString().equals(id);
        }
        return resolve(id).equals(ItemData.id(stack));
    }

    private static ItemStack[] countedSlots(PlayerInventory inv) {
        ItemStack[] storage = inv.getStorageContents();
        ItemStack[] slots = java.util.Arrays.copyOf(storage, storage.length + 1);
        slots[storage.length] = inv.getItemInOffHand();
        return slots;
    }

    @Override
    public int count(Player player, String id) {
        int total = 0;
        for (ItemStack stack : countedSlots(player.getInventory())) {
            if (matches(stack, id)) total += stack.getAmount();
        }
        return total;
    }

    @Override
    public boolean take(Player player, String id, int amount) {
        if (amount <= 0) return amount == 0;
        if (count(player, id) < amount) return false;
        int left = amount;
        for (ItemStack stack : countedSlots(player.getInventory())) {
            if (left == 0) break;
            if (!matches(stack, id)) continue;
            int n = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - n);
            left -= n;
        }
        return true;
    }

    @Override
    public void give(Player player, ItemStack stack) {
        if (ItemData.empty(stack)) return;
        for (ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItem(player.getLocation(), rest);
        }
    }

    @Override
    public void soulbind(ItemStack stack, boolean soulbound) {
        if (ItemData.empty(stack)) return;
        ItemData.soulbound(stack, soulbound);
        if (registry.def(stack) != null) renderer.render(stack);
        else renderer.renderVanilla(stack);
    }

    // ================================================================================== misc helpers

    /** Marks a (donation) stack as not repairable. */
    public void markNoRepair(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(ItemKeys.NO_REPAIR, PersistentDataType.BOOLEAN, true));
    }

    /** Units and artifacts carried by the player (spec 04 §3.1: at most 3, 4 with the Statue of Liberty). */
    public int countUnits(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemDef def = registry.def(stack);
            if (def != null && (def.kind() == ItemKind.UNIT || def.kind() == ItemKind.ARTIFACT)) total += stack.getAmount();
        }
        return total;
    }

    public boolean isSoulbound(ItemStack stack) {
        ItemDef def = registry.def(stack);
        return ItemData.soulbound(stack) || (def != null && def.has(com.civcraft.item.def.ItemFlag.SOULBOUND));
    }
}
